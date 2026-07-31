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
  statistic), **throughput**, **peak RSS** (self/children/total - GM/IM
  subprocess RSS tracked separately from JVM heap via explicit PID tracking,
  not `ps` regex matching)
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
repo's git history/scratchpad). A meaningful divergence there means this
harness has a bug, not a new finding.
