#!/usr/bin/env bash
set -euo pipefail

# Runs the full benchmark once per network-latency profile, re-applying
# Toxiproxy's toxics between runs. Host-side driver - run from the repo root.
#
# Usage:
#   ./run-sweep.sh                    # auto-detect physical-core topology (recommended)
#   ./run-sweep.sh <bench_cpuset>     # override bench's cpuset, infra still auto-detected
#   ./run-sweep.sh <bench_cpuset> <infra_cpuset>   # override both explicitly
#
# WHY AUTO-DETECT: SMT/hyperthreading means consecutive CPU numbers are often
# NOT distinct physical cores - e.g. a real `lscpu -e=CPU,CORE` seen on one of
# the target machines showed CPU 0,1 -> CORE 0, CPU 2,3 -> CORE 1, etc. Passing
# "0-7" there would only span 4 physical cores (double-booked via SMT), not 8 -
# badly distorting the GM/IM burst model this benchmark depends on being fair.
# This script instead reads `lscpu -p=CPU,CORE` and picks exactly ONE cpu id
# per distinct CORE for the bench cpuset, so it's correct on any machine's
# topology without the user having to work it out by hand each time.
#
# On an 8-physical-core machine, `bench` claiming one thread per core (8 cpus)
# leaves only the SIBLING threads of those same 8 cores for infra - some SMT
# contention between bench and infra is unavoidable there (there's no way to
# fully isolate both when bench alone wants all 8 physical cores), but this is
# still far better than double-booking the same physical core within `bench`
# itself, which is what a naive contiguous range risks.

detect_topology() {
  if ! command -v lscpu >/dev/null 2>&1; then
    echo "lscpu not found - this auto-detection only works on Linux. Pass an explicit cpuset instead:" >&2
    echo "  ./run-sweep.sh <bench_cpuset> [infra_cpuset]" >&2
    exit 1
  fi

  local bench_cpus=() infra_cpus=()
  declare -A seen_core=()
  while IFS=',' read -r cpu core; do
    [[ "$cpu" =~ ^#.*$ || -z "$cpu" ]] && continue
    if [[ -z "${seen_core[$core]:-}" ]]; then
      seen_core[$core]=1
      bench_cpus+=("$cpu")
    else
      infra_cpus+=("$cpu")
    fi
  done < <(lscpu -p=CPU,CORE)

  if [[ ${#bench_cpus[@]} -eq 0 ]]; then
    echo "Failed to parse lscpu output - falling back to explicit cpuset required." >&2
    exit 1
  fi

  BENCH_CPUSET_AUTO=$(IFS=,; echo "${bench_cpus[*]}")
  INFRA_CPUSET_AUTO=$(IFS=,; echo "${infra_cpus[*]:-${bench_cpus[*]}}")
}

# Only run lscpu-based detection when it's actually needed - if both cpusets
# were passed explicitly, honor them with zero lscpu dependency (matters on
# hosts without procps, e.g. running this from a Mac, or non-Linux containers).
if [[ $# -ge 2 ]]; then
  BENCH_CPUSET="$1"
  INFRA_CPUSET="$2"
else
  detect_topology
  BENCH_CPUSET="${1:-$BENCH_CPUSET_AUTO}"
  INFRA_CPUSET="${2:-$INFRA_CPUSET_AUTO}"
fi

echo "Detected/using: BENCH_CPUSET=$BENCH_CPUSET  INFRA_CPUSET=$INFRA_CPUSET"
echo "(one thread per distinct physical core for BENCH; verify with: lscpu -e=CPU,CORE)"
echo

# Export so every `docker compose` call below picks these up, including the
# minio/toxiproxy/seed bring-up right after this - a plain shell assignment
# does NOT propagate to child processes on its own, so without this export
# those specific commands were silently falling back to docker-compose.yml's
# hardcoded default cpuset ("${INFRA_CPUSET:-8-15}", sized for a 16-thread
# machine) instead of what was just detected/passed above.
export BENCH_CPUSET INFRA_CPUSET

mkdir -p results

# minio + toxiproxy are long-running and shared across the whole sweep, and
# seed only needs to populate MinIO's (tmpfs-backed, in-memory) bucket once -
# bring all three up explicitly here, then use --no-deps on every per-profile
# command below. Without --no-deps on the `bench run` step, `docker compose
# run` was reconciling bench's full dependency graph on every iteration,
# which re-triggered the "toxics" one-shot as an unsatisfied dependency. That
# second, implicit toxics run had no LATENCY_PROFILE set (only the explicit
# `docker compose up --force-recreate --no-deps toxics` line above it did),
# so it silently fell back to docker-compose.yml's `${LATENCY_PROFILE:-none}`
# default - resetting toxiproxy to a pass-through "none" config right before
# every single profile's actual benchmark run, which is why injected latency/
# bandwidth never showed up in results regardless of which profile was
# "applied" moments earlier.
docker compose up -d --wait minio toxiproxy
docker compose run --rm -T seed

failed_profiles=()

# Defaults to the profiles that actually bracket real S3 (see
# compose/toxiproxy/apply-toxics.sh for what each one models). The older
# az/region/degraded profiles are 10/2/0.4 Mbit/s links - 20-70x slower than
# real S3 - so they overstate the streaming benefit; run them explicitly with
#   PROFILES="none az region degraded" ./run-sweep.sh
PROFILES="${PROFILES:-s3real none s3fast s3cross}"

for profile in $PROFILES; do
  echo "=== Running latency profile: $profile ==="
  LATENCY_PROFILE="$profile" INFRA_CPUSET="$INFRA_CPUSET" \
    docker compose up --force-recreate --no-deps toxics

  # -T: no pseudo-TTY. These are non-interactive batch jobs, and a TTY only
  # adds control characters to the tee'd log.
  #
  # `|| true` on the pipeline plus an explicit PIPESTATUS check: WITHOUT this,
  # `set -euo pipefail` aborts the ENTIRE sweep the moment one profile's bench
  # container exits non-zero, so a crash in the first profile silently costs
  # you the other three - and prints no error saying why. Each profile is
  # independent, so a failure is recorded and the sweep carries on.
  BENCH_CPUSET="$BENCH_CPUSET" INFRA_CPUSET="$INFRA_CPUSET" RUN_ID="$profile" \
    docker compose run --rm --no-deps -T bench run 2>&1 | tee "results/summary-${profile}.txt" || true
  bench_status="${PIPESTATUS[0]}"

  if [[ "$bench_status" -ne 0 ]]; then
    failed_profiles+=("${profile}(exit=${bench_status})")
    echo
    echo "!!! profile '${profile}' FAILED: bench container exited ${bench_status}" >&2
    case "$bench_status" in
      137) echo "    exit 137 = SIGKILL, almost always the 8g mem_limit OOM-killer." >&2
           echo "    Check: docker inspect the run, or raise mem_limit in docker-compose.yml." >&2 ;;
      139) echo "    exit 139 = SIGSEGV, a native crash inside libvips/vips-ffm." >&2 ;;
      1)   echo "    exit 1 = the JVM threw; the stack trace should be in results/summary-${profile}.txt." >&2 ;;
    esac
    echo "    Continuing with the next profile." >&2
    echo
  fi
done

echo
if [[ ${#failed_profiles[@]} -gt 0 ]]; then
  echo "Sweep finished WITH FAILURES: ${failed_profiles[*]}"
  echo "Summaries (including partial ones) in ./results/summary-<profile>.txt"
  exit 1
fi
echo "All profiles complete. Summaries in ./results/summary-<profile>.txt"
