#!/usr/bin/env bash
set -euo pipefail

# Runs one of the standalone bash/hyperfine benchmarks this project's own
# README points back to (see "Cross-checking against the earlier bash
# benchmarks") - these predate run-sweep.sh's JVM/S3/Toxiproxy harness and
# operate directly on a local photo file with no S3/MinIO involved at all.
# Variant A's queue-drain number from run-sweep.sh should land in the same
# ballpark as benchmark_queue_drain.sh's GM/IM result on the same hardware;
# a meaningful divergence means the JVM harness has a bug, not a new finding.
#
# Usage:
#   ./run-legacy-benchmark.sh <photo.jpg> [script] [cpuset]
#
#   <photo.jpg>  REAL representative upload photo (~4000-8000px on the long
#                edge, i.e. the size real uploads arrive at before the app
#                downscales them) - synthetic/gradient/noise images decode
#                and compress very differently from real photographic
#                content and will bias every result below.
#
#   [script]     which benchmark to run - one of:
#                  resize            per-output-format timing (thumb/full/
#                                    webp/png/original), GM vs vips, single
#                                    image at a time - benchmark_resize.sh
#                  queue_drain       today's real deployment model (GM/IM:
#                                    cores*2 concurrent single-threaded
#                                    processes) vs the dedicated-vips-service
#                                    model (1 image at a time, full internal
#                                    threading) - benchmark_queue_drain.sh
#                  vips_sweep        concurrency x internal-threads-per-
#                                    process sweep, ranked by throughput -
#                                    benchmark_vips_sweep.sh
#                  vips_sweep_fixed  DEFAULT - same sweep, but with exact
#                                    kernel maxrss via GNU time (falls back
#                                    to a ps-polling sampler if unavailable)
#                                    and an in-memory pipe for the vips bleed
#                                    case instead of a temp-file round-trip -
#                                    benchmark_vips_sweep_fixed.sh
#
#   [cpuset]     --cpuset-cpus value, e.g. 0-7 - MUST be distinct physical
#                cores, not SMT siblings (see: lscpu -e=CPU,CORE, or just
#                run run-sweep.sh once and read its "Detected/using:" line).
#                Omit to let the container see every core.
#
# Examples:
#   ./run-legacy-benchmark.sh ~/photos/real-upload.jpg
#   ./run-legacy-benchmark.sh ~/photos/real-upload.jpg queue_drain 0-7

PHOTO="${1:-}"
SCRIPT="${2:-vips_sweep_fixed}"
CPUSET="${3:-}"

if [[ -z "$PHOTO" || ! -f "$PHOTO" ]]; then
  cat >&2 <<'EOF'
Usage: ./run-legacy-benchmark.sh <path-to-real-photo.jpg> [script] [cpuset]

  script: resize | queue_drain | vips_sweep | vips_sweep_fixed (default)
  cpuset: e.g. 0-7 - distinct physical cores only, see lscpu -e=CPU,CORE

See this script's header comment for what each one measures.
EOF
  exit 1
fi

case "$SCRIPT" in
  resize)           SCRIPT_PATH="/repository/benchmark_resize.sh" ;;
  queue_drain)       SCRIPT_PATH="/repository/benchmark_queue_drain.sh" ;;
  vips_sweep)        SCRIPT_PATH="/repository/benchmark_vips_sweep.sh" ;;
  vips_sweep_fixed)  SCRIPT_PATH="/repository/benchmark_vips_sweep_fixed.sh" ;;
  *)
    echo "Unknown script: $SCRIPT (expected resize|queue_drain|vips_sweep|vips_sweep_fixed)" >&2
    exit 1
    ;;
esac

IMAGE="gm-vips-bench"
echo "Building $IMAGE from Dockerfile.benchmark (cached layers make repeat runs fast)..."
docker build --platform linux/amd64 -f Dockerfile.benchmark -t "$IMAGE" .

PHOTO_ABS="$(cd "$(dirname "$PHOTO")" && pwd)/$(basename "$PHOTO")"
OUT_DIR="./results/legacy-${SCRIPT}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"
OUT_DIR_ABS="$(cd "$OUT_DIR" && pwd)"

CPUSET_ARGS=()
if [[ -n "$CPUSET" ]]; then
  CPUSET_ARGS=(--cpuset-cpus="$CPUSET")
  echo "Using cpuset: $CPUSET"
else
  echo "No cpuset given - container will see every core (pass one for Fargate/hardware parity, e.g. 0-7)"
fi

echo "Running $SCRIPT_PATH against $PHOTO_ABS, results in $OUT_DIR_ABS"
echo

docker run --rm --platform linux/amd64 "${CPUSET_ARGS[@]}" \
  -v "$PHOTO_ABS:/photo.jpg:ro" \
  -v "$OUT_DIR_ABS:/out" \
  "$IMAGE" "$SCRIPT_PATH" /photo.jpg /out

echo
echo "Done. Results in $OUT_DIR_ABS"
