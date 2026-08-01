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

detect_topology

BENCH_CPUSET="${1:-$BENCH_CPUSET_AUTO}"
INFRA_CPUSET="${2:-$INFRA_CPUSET_AUTO}"

echo "Detected/using: BENCH_CPUSET=$BENCH_CPUSET  INFRA_CPUSET=$INFRA_CPUSET"
echo "(one thread per distinct physical core for BENCH; verify with: lscpu -e=CPU,CORE)"
echo

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
docker compose run --rm seed

for profile in none az region degraded; do
  echo "=== Running latency profile: $profile ==="
  LATENCY_PROFILE="$profile" INFRA_CPUSET="$INFRA_CPUSET" \
    docker compose up --force-recreate --no-deps toxics

  BENCH_CPUSET="$BENCH_CPUSET" INFRA_CPUSET="$INFRA_CPUSET" RUN_ID="$profile" \
    docker compose run --rm --no-deps bench run 2>&1 | tee "results/summary-${profile}.txt"
done

echo
echo "All profiles complete. Summaries in ./results/summary-<profile>.txt"
