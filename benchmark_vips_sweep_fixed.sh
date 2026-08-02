#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# Sweep benchmark: current GM/IM production model  vs  a range of possible
# libvips deployment models (concurrency C x internal-threads-per-process T).
# ============================================================================

SRC="${1:-}"
OUT_DIR="${2:-$(mktemp -d)}"

if [[ -z "$SRC" || ! -f "$SRC" ]]; then
  cat >&2 <<'EOF'
Usage: benchmark_vips_sweep.sh <path-to-real-source-photo.jpg> [output-dir]
EOF
  exit 1
fi

for bin in gm convert vips vipsthumbnail python3 hyperfine; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Missing required binary: $bin" >&2; exit 1; }
done

# Detect GNU time for accurate kernel-level Peak RSS tracking
TIME_BIN=""
if command -v gtime >/dev/null 2>&1; then
  TIME_BIN="gtime"
else
  SYS_TIME=$(type -p time 2>/dev/null || true)
  if [[ -n "$SYS_TIME" ]] && "$SYS_TIME" -f "%M" echo test >/dev/null 2>&1; then
    TIME_BIN="$SYS_TIME"
  fi
fi

mkdir -p "$OUT_DIR"
NCORES=$(nproc 2>/dev/null || sysctl -n hw.ncpu)
QUEUE_SIZE=$((NCORES * 2))
echo "Detected cores: $NCORES   Queue size (cores*2): $QUEUE_SIZE"
if [[ -n "$TIME_BIN" ]]; then
  echo "Memory measurement tool: $TIME_BIN (exact kernel maxrss)"
else
  echo "Memory measurement tool: ps polling sampler (fallback)"
fi

ICC="conf/imagemagick/sRGB.icc"
[[ -f "$ICC" ]] || echo "WARNING: $ICC not found relative to cwd ($PWD) - run from repo root." >&2

read -r SRC_W SRC_H <<<"$(gm identify -format "%w %h" "$SRC")"

area_fit() {
  local target_px=$1
  awk -v w="$SRC_W" -v h="$SRC_H" -v px="$target_px" 'BEGIN {
    scale = sqrt(px / (w*h)); if (scale > 1) scale = 1;
    printf "%dx%d", int(w*scale+0.5), int(h*scale+0.5)
  }'
}

FULL=$(area_fit 2250000)   # PictureFormat.mobileFullFormat (1500x1500 -> 2MP)

BLEED_PX=36
CONTENT_W=2480
CONTENT_H=3508
FULL_W=$((CONTENT_W + 2 * BLEED_PX))
FULL_H=$((CONTENT_H + 2 * BLEED_PX))

echo "mobileFull target: ${FULL}   bleed canvas: ${FULL_W}x${FULL_H} (content ${CONTENT_W}x${CONTENT_H}, ${BLEED_PX}px bleed)"
echo

RESULTS_STD="$OUT_DIR/results_standard.tsv"
RESULTS_BLEED="$OUT_DIR/results_bleed.tsv"
: > "$RESULTS_STD"
: > "$RESULTS_BLEED"

# ---- Fallback peak RSS sampler ----
start_sampler() {
  local sample_file=$1
  ( while true; do
      ps -eo rss,comm | awk '$2 ~ /gm|convert|vips/ {sum+=$1} END{print sum+0}' >> "$sample_file"
      sleep 0.02
    done ) >/dev/null 2>&1 &
  echo $!
}

peak_rss_mb_from_sampler() {
  local sample_file=$1
  if [[ -s "$sample_file" ]]; then
    python3 -c "print($(sort -rn "$sample_file" | head -1)/1024)"
  else
    echo "0.0"
  fi
}

# ---- run_queue: drains QUEUE_SIZE jobs at external concurrency $conc ----
run_queue() {
  local label=$1 cmd_tmpl=$2 ext=$3 conc=$4 results_file=$5
  local batch_dir; batch_dir=$(mktemp -d)
  local sample_file="$batch_dir/samples.txt"
  local time_output="$batch_dir/time.txt"
  local job_out_dir="$batch_dir/out"; mkdir -p "$job_out_dir"

  # Clean execution script (evaluates pipes/env inline without nested subshells)
  local job_script="$batch_dir/job.sh"
  cat > "$job_script" <<'JOBSCRIPT'
#!/usr/bin/env bash
set -euo pipefail
i="$1"
out="$JOB_OUT_DIR/job_${i}.$JOB_EXT"
cmd="${CMD_TMPL//\{OUT\}/$out}"
eval "$cmd"
JOBSCRIPT
  chmod +x "$job_script"

  export JOB_OUT_DIR="$job_out_dir" JOB_EXT="$ext" CMD_TMPL="$cmd_tmpl"

  local bench_cmd="seq 1 $QUEUE_SIZE | xargs -P $conc -I{} '$job_script' {}"
  local sampler_pid=""

  if [[ -z "$TIME_BIN" ]]; then
    sampler_pid=$(start_sampler "$sample_file")
  else
    bench_cmd="$TIME_BIN -f '%M' -o '$time_output' $bench_cmd"
  fi

  hyperfine \
    --warmup 1 --min-runs 5 \
    --export-json "$batch_dir/hf.json" \
    "$bench_cmd" \
    >/dev/null

  if [[ -n "$sampler_pid" ]]; then
    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true
  fi

  local mean stddev throughput rss
  mean=$(python3 -c "import json; print(json.load(open('$batch_dir/hf.json'))['results'][0]['mean'])")
  stddev=$(python3 -c "import json; print(json.load(open('$batch_dir/hf.json'))['results'][0]['stddev'])")
  throughput=$(python3 -c "print($QUEUE_SIZE/$mean)")

  if [[ -n "$TIME_BIN" && -s "$time_output" ]]; then
    # GNU time reports KB on Linux
    rss=$(python3 -c "print($(cat "$time_output")/1024)")
  else
    rss=$(peak_rss_mb_from_sampler "$sample_file")
  fi

  printf "%-38s conc=%-4d drain=%7.2fs±%5.2fs  throughput=%6.2f/s  peakRSS=%7.1fMB\n" \
    "$label" "$conc" "$mean" "$stddev" "$throughput" "$rss"
  printf "%s\t%d\t%.3f\t%.3f\t%.3f\t%.1f\n" "$label" "$conc" "$mean" "$stddev" "$throughput" "$rss" >> "$results_file"

  rm -rf "$batch_dir"
}

# ---- build the vips (C, T) sweep ----
build_sweep() {
  local -a factors=(1 2 4 8 16 32)
  local seen=""
  echo "$((NCORES * 2)) 1"   # mimics GM/IM's oversubscription model
  for f in "${factors[@]}"; do
    if (( f <= NCORES )); then
      local c=$(( NCORES / f ))
      (( c < 1 )) && c=1
      local key="${c}x${f}"
      if [[ "$seen" != *"|$key|"* ]]; then
        seen="$seen|$key|"
        echo "$c $f"
      fi
    fi
  done
}

# ============================================================================
echo "=== Standard resize: drain queue of $QUEUE_SIZE mobileFull (${FULL}) images ==="
GM_CMD="gm convert '$SRC' -auto-orient -profile '$ICC' -strip -colorspace RGB -quality 85 -type truecolor -background '#ffffff' -flatten -filter Bessel -resize '${FULL}!' 'jpg:{OUT}'"

run_queue "GM (production model)" "$GM_CMD" jpg "$QUEUE_SIZE" "$RESULTS_STD"

while read -r conc threads; do
  VIPS_CMD="VIPS_CONCURRENCY=$threads vipsthumbnail '$SRC' --size '${FULL}!' -o '{OUT}[Q=85,strip]'"
  run_queue "vips (conc=${conc} x threads=${threads})" "$VIPS_CMD" jpg "$conc" "$RESULTS_STD"
done < <(build_sweep)

echo
echo "=== Bleed resize: drain queue of $QUEUE_SIZE bleed page (${FULL_W}x${FULL_H}) images ==="
IM_CMD="convert -size ${FULL_W}x${FULL_H} '$SRC' -auto-orient -profile '$ICC' -strip -colorspace sRGB -quality 85 -alpha remove -type truecolor -resize '${CONTENT_W}x${CONTENT_H}^' -virtual-pixel Mirror -set option:distort:viewport '%[fx:w+$((2*BLEED_PX))]x%[fx:h+$((2*BLEED_PX))]-${BLEED_PX}-${BLEED_PX}' -distort SRT 0 -gravity center -extent ${FULL_W}x${FULL_H} 'jpg:{OUT}'"

run_queue "IM (production model)" "$IM_CMD" jpg "$QUEUE_SIZE" "$RESULTS_BLEED"

while read -r conc threads; do
  # In-memory stream pipeline: thumbnail stdout -> embed stdin -> file
  VIPS_BLEED_CMD="VIPS_CONCURRENCY=$threads vipsthumbnail '$SRC' --size '${CONTENT_W}x${CONTENT_H}' --smartcrop centre -o .jpg | VIPS_CONCURRENCY=$threads vips embed stdin '{OUT}[Q=85,strip]' ${BLEED_PX} ${BLEED_PX} ${FULL_W} ${FULL_H} --extend mirror"
  run_queue "vips (conc=${conc} x threads=${threads})" "$VIPS_BLEED_CMD" jpg "$conc" "$RESULTS_BLEED"
done < <(build_sweep)

# ============================================================================
echo
echo "=== Ranked summary: standard resize (by throughput, best first) ==="
sort -t$'\t' -k5 -rn "$RESULTS_STD" | awk -F'\t' '{printf "%2d. %-38s throughput=%6.2f/s  drain=%6.2fs  peakRSS=%7.1fMB\n", NR, $1, $5, $3, $6}'

echo
echo "=== Ranked summary: bleed resize (by throughput, best first) ==="
sort -t$'\t' -k5 -rn "$RESULTS_BLEED" | awk -F'\t' '{printf "%2d. %-38s throughput=%6.2f/s  drain=%6.2fs  peakRSS=%7.1fMB\n", NR, $1, $5, $3, $6}'

echo
echo "Full raw results (tsv): $RESULTS_STD  $RESULTS_BLEED"