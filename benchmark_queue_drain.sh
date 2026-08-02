#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# "Queue drain" benchmark: given a burst of `cores * 2` images arriving at
# once (matching AppController's imageThreadPool size), how long does each
# approach take to fully process all of them - using each library the way
# it would ACTUALLY be deployed, not artificially handicapped for fairness.
#
#   - GM / ImageMagick: cores*2 CONCURRENT single-threaded processes.
#     This is today's real production model - a thread pool of N workers,
#     each running one single-threaded gm/convert subprocess at a time.
#
#   - libvips: cores*2 images processed SEQUENTIALLY (one process at a
#     time), each using vips' default internal multi-core parallelism.
#     This is the natural way to run vips in a dedicated image-processing
#     service built around it - lean on its own per-image threading instead
#     of spawning many external processes.
#
# This deliberately does NOT cap VIPS_CONCURRENCY - unlike the earlier
# fairness-focused benchmark, here we WANT vips to use all cores per image,
# because that per-image internal parallelism is the actual advantage being
# evaluated. The metric that matters is total wall time to drain the queue,
# not per-process CPU parity.
# ============================================================================

SRC="${1:-}"
OUT_DIR="${2:-$(mktemp -d)}"

if [[ -z "$SRC" || ! -f "$SRC" ]]; then
  cat >&2 <<'EOF'
Usage: benchmark_queue_drain.sh <path-to-real-source-photo.jpg> [output-dir]

Pass a REAL representative upload photo (see benchmark_resize.sh notes).
EOF
  exit 1
fi

for bin in gm convert vips vipsthumbnail python3 hyperfine; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Missing required binary: $bin" >&2; exit 1; }
done

mkdir -p "$OUT_DIR"
NCORES=$(sysctl -n hw.ncpu 2>/dev/null || nproc)
QUEUE_SIZE=$((NCORES * 2))   # matches api/.../AppController.scala:71 imageThreadPool sizing
echo "Detected cores: $NCORES   Queue size (cores*2): $QUEUE_SIZE"

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

# ---- peak RSS sampler, spans one hyperfine invocation (warmup + measured runs) ----
start_sampler() {
  local sample_file=$1
  ( while true; do
      ps -eo rss,comm | awk '$2 ~ /gm|convert|vips/ {sum+=$1} END{print sum+0}' >> "$sample_file"
      sleep 0.05
    done ) >/dev/null 2>&1 &
  echo $!
}

peak_rss_mb() {
  local sample_file=$1
  if [[ -s "$sample_file" ]]; then
    python3 -c "print($(sort -rn "$sample_file" | head -1)/1024)"
  else
    echo "0.0"
  fi
}

# ---- run_burst: cores*2 concurrent single-threaded jobs (GM/IM production model) ----
# args: label, cmd_tmpl (with {OUT} placeholder)
run_burst() {
  local label=$1 cmd_tmpl=$2 ext=$3
  local batch_dir; batch_dir=$(mktemp -d)
  local sample_file="$batch_dir/samples.txt"
  local job_out_dir="$batch_dir/out"; mkdir -p "$job_out_dir"

  local job_script="$batch_dir/job.sh"
  cat > "$job_script" <<'JOBSCRIPT'
#!/usr/bin/env bash
i="$1"
out="$JOB_OUT_DIR/job_${i}.$JOB_EXT"
cmd="${CMD_TMPL//\{OUT\}/$out}"
bash -c "$cmd"
JOBSCRIPT
  chmod +x "$job_script"

  export JOB_OUT_DIR="$job_out_dir" JOB_EXT="$ext" CMD_TMPL="$cmd_tmpl"

  local sampler_pid; sampler_pid=$(start_sampler "$sample_file")

  hyperfine \
    --warmup 1 --min-runs 5 \
    --export-json "$batch_dir/hf.json" \
    "seq 1 $QUEUE_SIZE | xargs -P $QUEUE_SIZE -I{} '$job_script' {}" \
    >/dev/null

  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" 2>/dev/null || true

  local mean stddev throughput rss
  mean=$(python3 -c "import json; print(json.load(open('$batch_dir/hf.json'))['results'][0]['mean'])")
  stddev=$(python3 -c "import json; print(json.load(open('$batch_dir/hf.json'))['results'][0]['stddev'])")
  throughput=$(python3 -c "print($QUEUE_SIZE/$mean)")
  rss=$(peak_rss_mb "$sample_file")

  printf "%-45s queue=%-3d  drain=%6.2fs±%5.2fs  throughput=%6.2f/s  peakRSS=%7.1fMB   [model: %d concurrent single-threaded processes]\n" \
    "$label" "$QUEUE_SIZE" "$mean" "$stddev" "$throughput" "$rss" "$QUEUE_SIZE"

  rm -rf "$batch_dir"
}

# ---- run_sequential: cores*2 jobs processed one at a time, each using vips' ----
# ---- default full-core internal parallelism (the dedicated-service model) ----
# args: label, cmd_tmpl (with {OUT} placeholder)
run_sequential() {
  local label=$1 cmd_tmpl=$2 ext=$3
  local batch_dir; batch_dir=$(mktemp -d)
  local sample_file="$batch_dir/samples.txt"
  local job_out_dir="$batch_dir/out"; mkdir -p "$job_out_dir"

  local job_script="$batch_dir/job.sh"
  cat > "$job_script" <<'JOBSCRIPT'
#!/usr/bin/env bash
for i in $(seq 1 "$QUEUE_SIZE"); do
  out="$JOB_OUT_DIR/job_${i}.$JOB_EXT"
  cmd="${CMD_TMPL//\{OUT\}/$out}"
  bash -c "$cmd"
done
JOBSCRIPT
  chmod +x "$job_script"

  export JOB_OUT_DIR="$job_out_dir" JOB_EXT="$ext" CMD_TMPL="$cmd_tmpl" QUEUE_SIZE="$QUEUE_SIZE"

  local sampler_pid; sampler_pid=$(start_sampler "$sample_file")

  hyperfine \
    --warmup 1 --min-runs 5 \
    --export-json "$batch_dir/hf.json" \
    "'$job_script'" \
    >/dev/null

  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" 2>/dev/null || true

  local mean stddev throughput rss
  mean=$(python3 -c "import json; print(json.load(open('$batch_dir/hf.json'))['results'][0]['mean'])")
  stddev=$(python3 -c "import json; print(json.load(open('$batch_dir/hf.json'))['results'][0]['stddev'])")
  throughput=$(python3 -c "print($QUEUE_SIZE/$mean)")
  rss=$(peak_rss_mb "$sample_file")

  printf "%-45s queue=%-3d  drain=%6.2fs±%5.2fs  throughput=%6.2f/s  peakRSS=%7.1fMB   [model: 1 process at a time, full %d-core internal threading]\n" \
    "$label" "$QUEUE_SIZE" "$mean" "$stddev" "$throughput" "$rss" "$NCORES"

  rm -rf "$batch_dir"
}

# ============================================================================
echo "=== Standard resize: drain a queue of $QUEUE_SIZE mobileFull (${FULL}) images ==="
GM_CMD="gm convert '$SRC' -auto-orient -profile '$ICC' -strip -colorspace RGB -quality 85 -type truecolor -background '#ffffff' -flatten -filter Bessel -resize '${FULL}!' 'jpg:{OUT}'"
VIPS_CMD="vipsthumbnail '$SRC' --size '${FULL}!' -o '{OUT}[Q=85,strip]'"   # VIPS_CONCURRENCY left at default (all cores)

run_burst      "GM   mobileFull (production model)"        "$GM_CMD"   jpg
run_sequential "vips mobileFull (dedicated-service model)"  "$VIPS_CMD" jpg

echo
echo "=== Bleed resize: drain a queue of $QUEUE_SIZE bleed page (${FULL_W}x${FULL_H}) images ==="
IM_CMD="convert -size ${FULL_W}x${FULL_H} '$SRC' -auto-orient -profile '$ICC' -strip -colorspace sRGB -quality 85 -alpha remove -type truecolor -resize '${CONTENT_W}x${CONTENT_H}^' -virtual-pixel Mirror -set option:distort:viewport '%[fx:w+$((2*BLEED_PX))]x%[fx:h+$((2*BLEED_PX))]-${BLEED_PX}-${BLEED_PX}' -distort SRT 0 -gravity center -extent ${FULL_W}x${FULL_H} 'jpg:{OUT}'"
VIPS_BLEED_CMD="bash -c \"vipsthumbnail '$SRC' --size '${CONTENT_W}x${CONTENT_H}' --smartcrop centre -o '{OUT}.crop.jpg[Q=85,strip]' && vips embed '{OUT}.crop.jpg' '{OUT}[Q=85]' ${BLEED_PX} ${BLEED_PX} ${FULL_W} ${FULL_H} --extend mirror\""

run_burst      "IM   bleed (production model)"              "$IM_CMD"        jpg
run_sequential "vips bleed (dedicated-service model)"        "$VIPS_BLEED_CMD" jpg

echo
echo "Done. 'drain' = total time to fully process the whole queue of $QUEUE_SIZE images."
echo "This is the number that actually answers 'is vips faster for real traffic bursts' -"
echo "each library ran the way it would really be deployed, not equalized for fairness."