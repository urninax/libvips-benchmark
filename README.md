# libvips-benchmark

Independent benchmark comparing two image-processing deployment models against
S3-compatible storage, for evaluating a GraphicsMagick/ImageMagick → libvips
migration ahead of a planned move to presigned-URL direct-to-S3 uploads.

This is a **fully standalone project** - no dependency on the `core` repo's
build/toolchain. A few command/config values (GM/IM commands, bleed math,
AWS SDK version, IM/GM apt install steps) were mirrored from `core` for
realism/version-parity, but nothing here imports or builds against it.

## What this answers

1. **Is libvips itself faster than GM/IM** for this workload? (Variant A vs. B0)
2. **Does streaming directly from S3 into libvips - and back out - provide a
   further real win** over the traditional fetch-full-object→process→push
   pattern GM/IM are structurally stuck with? (B0 vs. B3)
3. **Does that streaming win hold up under realistic network latency**, or
   does it only look good on a near-zero-latency local MinIO? (via the
   Toxiproxy latency-profile sweep)

## Variant grid

|  | buffered fetch | streaming fetch |
|---|---|---|
| **buffered put** | B0 (isolates: is libvips itself faster?) | B1 |
| **streaming put** | B2 | B3 (full streaming - the headline candidate) |

Plus the GM/IM baseline (Variant A), mirroring today's real production
pattern: fetch full object → temp file → `gm convert`/`convert` subprocess →
temp file → PutObject.

Concurrency model is **fixed, not swept**: `cores*2` workers for every
variant (GM/IM single-threaded subprocesses; vips workers with
`VIPS_CONCURRENCY=1` each) - this is the already-proven-optimal deployment
model from earlier bash/hyperfine benchmarking on this same hardware, reused
here rather than re-litigated, so this benchmark's effort goes entirely
toward the new streaming-vs-buffered-I/O question.

## Prerequisites

- Docker with `linux/amd64` support (native on the target Ryzen machines, no
  QEMU needed - unlike development on Apple Silicon)
- `docker compose` v2

## Quick start

```bash
# 1. Verify which cores are DISTINCT PHYSICAL cores before running anything -
#    both target machines (Ryzen 7 4800H, Ryzen 7 7800X3D) are 8C/16T:
lscpu -e=CPU,CORE

# 2. Bring up MinIO + Toxiproxy, seed fixtures, run the benchmark:
docker compose up --build --abort-on-container-exit bench

# Results print to stdout AND land in ./results/
```

## Latency-profile sweep (the actual point of this benchmark)

```bash
./run-sweep.sh 0-7   # replace 0-7 with your verified distinct-physical-core range
```

## Build once, run on multiple machines (push to a registry)

Since the `bench` image is self-contained (JDK, native libs, the jar, sample
fixtures), you don't need to rebuild it on every machine - build once and push
to Docker Hub (or any registry), then other machines just pull.

**On the machine that builds** (ideally a real `linux/amd64` box - building
here on Apple Silicon works via `--platform linux/amd64` but goes through slow
QEMU emulation; native amd64 is much faster):

```bash
export BENCH_IMAGE=yourdockerhubuser/libvips-bench:latest
docker compose build bench          # tags it as $BENCH_IMAGE
docker login                        # if not already
docker push "$BENCH_IMAGE"
```

**On any other machine** - only `docker-compose.yml`, `compose/`, and
`.env`/exported `BENCH_IMAGE` are actually needed; `seed` reuses the same
pushed image, so nothing else needs pulling from source:

```bash
export BENCH_IMAGE=yourdockerhubuser/libvips-bench:latest
docker compose pull bench seed      # or just let `up` pull implicitly
./run-sweep.sh 0-7                  # same as any other run
```

`BENCH_IMAGE` defaults to `libvips-bench` (a local-only tag) if unset, so
local `docker compose build` keeps working exactly as before - this is purely
additive. All target machines are `linux/amd64`, so a single-arch tag is
sufficient; no need for a multi-arch manifest.

Runs the full benchmark once per profile (`none` / `az` / `region` /
`degraded` - see `compose/toxiproxy/apply-toxics.sh` for exact latency/
bandwidth values), re-applying Toxiproxy's toxics between runs. Summaries land
in `./results/summary-<profile>.txt`.

**Toxiproxy caveat**: it delays buffered chunks in userspace and doesn't model
TCP congestion-window growth. Treat results as directionally correct for the
streaming-vs-buffered *trend* as latency increases, not as precise
predictions of real S3 behavior. A confirmation run against real S3 (point
`S3_ENDPOINT` at nothing / unset it and provide real AWS credentials +
`S3_PATH_STYLE=false`) is the way to validate the trend against reality.

## Metrics reported

- **Queue drain time** (mean±stddev over measured reps, matching `hyperfine`'s
  statistic), **throughput** (successful jobs only - a variant that fails fast
  is not credited as "faster")
- **Peak total memory per variant** (`totalMB`) - each of the six variants
  (GM, IM, vips b0-b3) runs in its own child JVM process, spawned fresh by
  the top-level orchestrator (`Main.run-variant`), specifically so this
  number is trustworthy: earlier, all six shared one continuous JVM and a
  vips variant's peak memory was contaminated by whatever GM/IM (or an
  earlier vips variant) had already left resident - glibc doesn't return
  freed memory to the OS, so a shared-process baseline-and-subtract approach
  cannot isolate a later variant's own memory use. Per-process isolation
  removes that failure mode entirely, and the baseline is captured AFTER
  warmup rather than before it, so warmup's own one-time JIT/cold-start
  cost isn't folded into "what the measured reps needed" either.
  Measured as **PSS** (proportional set size, via `/proc/<pid>/smaps_rollup`),
  not raw RSS - GM/IM's children are 8 separate processes mapping the same
  shared libraries (libjpeg, libGraphicsMagick, glibc), and summing raw RSS
  across siblings would count every shared page once per process (~8x
  inflation); PSS divides shared pages by their mapper count, so GM/IM's
  multi-process concurrency model isn't penalized by a measurement artifact
  relative to vips' single-process one. `rssChild`/`vipsPeak` remain as
  secondary, engine-specific figures (forked gm/convert PSS; libvips' own
  tracked-allocation counter, which undercounts codec scratch memory) - see
  `ResultTable`'s footer for exactly what each measures.
- **`MB/thru`** (`totalMB` / `thru/s`) - memory needed to sustain this rate of
  throughput, the right column for an actual memory-EFFICIENCY claim between
  engines. `totalMB` alone is bounded mostly by the FIXED concurrency (every
  variant runs at the same `cores*2` workers by design), so a faster engine
  can look memory-equivalent to a slower one purely because peak concurrent
  memory doesn't shrink just because the queue drains quicker. `MB/thru`
  correctly credits a faster engine for doing more work in that same
  peak-memory window. (A naive `totalMB / jobs-completed` was considered and
  rejected - `totalMB` is a peak, not a cumulative sum, so dividing by a
  volume with no time term makes the number shrink arbitrarily as
  `measuredReps`/queue size grow, even though peak concurrency never changes.)
- **Allocator** (`BENCH_ALLOCATOR=glibc|jemalloc`, default `glibc`) - vips' 8
  concurrent worker threads share ONE process, and glibc can hand out a
  separate malloc arena per thread to reduce lock contention; none of the PSS
  fixes above touch that, so vips' memory figures may still carry some
  multi-threaded-arena overhead GM/IM's multi-process model doesn't pay (each
  `gm`/`convert` child is single-threaded, one arena). Setting
  `BENCH_ALLOCATOR=jemalloc` swaps the allocator via `LD_PRELOAD` in
  `entrypoint.sh`, applied to the WHOLE container - every variant subprocess
  and every `gm`/`convert` child, not vips only - so it stays an A/B
  comparison rather than tuning the config that happens to favor one engine.
  Default stays `glibc` so existing results remain comparable; the active
  allocator is echoed in both `entrypoint.sh`'s startup log line and the
  `allocator=` field of the run banner, so it travels with any pasted results.
- **Per-job latency percentiles** (p50/p95/p99/max), pooled across measured reps
- **`overlap ratio`** = `(fetch+process+upload)/total` - the direct
  quantitative proof of whether streaming actually overlapped I/O and compute.
  ≈1.0 for Variant A (phases are genuinely disjoint there); >1.0 for B3 is the
  headline number.
- **CPU utilization** (JVM + reaped GM/IM children) and **GC pause time/count**
  per rep

## Known limitations / follow-ups (not blocking v1)

- **JPEG only.** No HEIF - real production traffic includes HEIF/HEIC
  uploads; adding it needs libvips built/installed with libheif support and a
  representative fixture. Flagged as a follow-up, not in this benchmark.
- **Progressive JPEG forces vips to buffer internally** (documented libvips
  limitation, not a bug) - this is intentionally included as a fixture
  (`samples/progressive.jpg`) to demonstrate the edge case, not hide it.
- **Output-correctness is not automatically gated in v1** - GM's `-filter
  Bessel` vs. vips' default Lanczos3, and a real `RGB`/`sRGB` colorspace
  naming inconsistency already present in the real repo's own GM vs. IM
  commands, mean outputs will differ slightly. Compare output images manually
  before fully trusting timing numbers; an automated `--verify` perceptual-diff
  gate is a natural next addition.
- **vips-ffm API surface was verified by reading the library's source
  directly during planning, not compile-checked against the real JDK 23+
  toolchain in the authoring environment.** First `mvn compile` inside the
  Docker build is expected to surface any final signature corrections in
  `VipsStreamingVariant.java` - treat that file as the spike to finish, not
  as pre-verified.

## Cross-checking against the earlier bash benchmarks

Variant A's queue-drain number from this JVM harness should land in the same
ballpark as the equivalent `benchmark_queue_drain.sh` result on the same
hardware (from the earlier bash/hyperfine-based investigation in the `core`
repo's git history/scratchpad, now vendored into this repo - see below). A
meaningful divergence there means this harness has a bug, not a new finding.

These scripts predate the JVM/S3/Toxiproxy harness above and are a
completely separate execution model: no S3/MinIO/Docker Compose stack, no
streaming question - just GM/IM vs `vipsthumbnail`/`vips` CLI commands run
directly against a **local photo file** via `hyperfine`, matching the
concurrency sweep from the original investigation. Run them via
`Dockerfile.benchmark` (a second, separate image - GM/IM/libvips-tools/
hyperfine, not the JDK 23/vips-ffm image the main benchmark uses):

```bash
./run-legacy-benchmark.sh <path-to-real-photo.jpg> [script] [cpuset]

# script: resize | queue_drain | vips_sweep | vips_sweep_fixed (default)
# cpuset: e.g. 0-7 - distinct physical cores only, see lscpu -e=CPU,CORE
```

- `resize` - per-output-format timing (thumb/full/webp/png/original), GM vs
  vips, single image at a time
- `queue_drain` - today's real deployment model (GM/IM: `cores*2` concurrent
  single-threaded processes) vs a dedicated-vips-service model (one image at
  a time, full internal threading)
- `vips_sweep` / `vips_sweep_fixed` - concurrency × internal-threads-per-
  process sweep, ranked by throughput; `_fixed` additionally gets exact
  kernel maxRSS via GNU `time` (falls back to a `ps`-polling sampler if
  unavailable) and an in-memory pipe for the vips bleed case instead of a
  temp-file round-trip - prefer `_fixed` unless reproducing the original
  investigation's exact numbers

**Pass a REAL, representative upload photo** (~4000-8000px on the long
edge - the size real uploads arrive at before the app downscales them).
Synthetic/gradient/noise images decode and compress very differently from
real photographic content and will bias every result. Results land in
`./results/legacy-<script>-<timestamp>/`.
