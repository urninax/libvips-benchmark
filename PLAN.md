# FFM (vips-ffm) Streaming Benchmark Prototype

## Context

Journi is evaluating replacing GraphicsMagick/ImageMagick with libvips for its photobook image pipeline. Prior CLI/bash benchmarking in this investigation already established (on the user's real hardware) that vips beats GM/IM by ~7-9x throughput and ~5-7x peak memory for plain resize and bleed, when run as many concurrent single-threaded processes — that concurrency question is considered answered and is **not** being re-litigated here.

The team is now also planning a **presigned-URL direct-to-S3 upload architecture**, meaning the extracted image-processing service will need to fetch source images from S3 rather than receive them in the HTTP request body as today. This raises a genuinely new question: does streaming directly from S3 into libvips (and back out to S3) — instead of the "fetch full object → process → push full object" pattern IM/GM are structurally stuck with — provide a further real win, and does that hold up under realistic network latency (the user's explicit concern: "I don't know how different network latency is when fetching or streaming").

`vips-ffm` (Maven Central `app.photofox.vips-ffm:vips-ffm-core:1.9.8`, JDK 23+, FFM API) is the only viable Java option for true native libvips streaming. Verified directly from source (not guessed) that `VImage.newFromSource`/`newFromStream`/`writeToTarget`/`writeToStream`/`thumbnailSource`, and `embed(..., extend=MIRROR)` all exist and cover the exact GM/IM operation chain needed. The JDK 23+ requirement is low-risk since the image processor is already being extracted into its own service — it doesn't need to match the rest of the monorepo's JDK 21.

This is a **fully independent project, not part of the "core" repo at all** — it lives at `~/IdeaProjects/libvips-benchmark/` (sibling to `core`, its own git repo, no dependency on core's build/sbt/toolchain). It runs in Docker on the user's real Linux hardware (Ryzen 7 4800H laptop, 8C/16T, or Ryzen 7 7800X3D desktop). The existing `core/libvips-benchmark/sample_original.jpg` (the user's own earlier local testing artifact, currently untracked in the core repo) gets copied into the new independent project as its base fixture image, so results stay comparable with the earlier bash benchmarks — but the new project itself has no path/build dependency back on `core`.

## Scope decisions (confirmed with user)

- **Full B0-B3 variant grid**, not just "GM/IM vs one vips variant" — this is the only way to attribute a win to "libvips is faster" vs. "streaming overlaps I/O and compute" separately.
- **JPEG only for v1** (baseline + progressive) — no HEIF. HEIF is real production traffic but adds a real dependency/setup risk; flagged as a documented follow-up, not blocking.
- **Reuse the already-proven vips deployment model** (many concurrent single-threaded processes / workers, `VIPS_CONCURRENCY=1`) rather than re-sweeping concurrency in this harness. This benchmark's effort goes entirely toward the new streaming-vs-buffered-I/O axis.
- **Toxiproxy included in v1** for configurable network-latency injection — without it, MinIO's near-zero baseline latency means the streaming variant can only ever look pointless, answering nothing about the real motivating question.

## Architecture

### The variant grid (the actual deliverable)

| | buffered fetch | streaming fetch |
|---|---|---|
| **buffered put** | B0 (isolates: is libvips itself faster?) | B1 |
| **streaming put** | B2 | B3 (full streaming — the headline candidate) |

Plus the **GM/IM baseline** (Variant A): fetch full object via `GetObject`→buffer→temp file→`gm convert`/`convert` subprocess (exact commands mirrored from `GraphicsMagickClient.buildCmd`/`ImageMagickClient.buildCmd`+`bleedOperation`, verified against the real repo)→temp file→`PutObject`. Built as argv lists (`ProcessBuilder`, never a single shell string — the real Scala code's string-based `scala.sys.process` invocation is a latent quoting risk not worth replicating).

B0 vs. A isolates the library. B3 vs. B0 isolates streaming. That's the number that actually answers the migration question.

### Concurrency model (fixed, not swept)

- **GM/IM**: `cores*2` workers, each a single-threaded subprocess — mirrors `imageThreadPool` (`api/app/com/journi/api/controller/AppController.scala:71`, `Runtime.getRuntime.availableProcessors * 2`, verified).
- **vips (all B-variants)**: `cores*2` workers, `VIPS_CONCURRENCY=1` each — the already-proven-optimal "drop-in replacement" model from earlier bash benchmarking.

Queue = pre-filled `BlockingQueue<String>` of S3 keys, `poll()`-until-empty (not `take()`), matching the burst semantics of the earlier `xargs -P` bash benchmarks. 1-2 warmup + 5 measured drain repetitions **within one JVM process** (avoids re-measuring JVM startup/JIT warmup per repetition, unlike wrapping the whole JVM in `hyperfine`).

### Streaming implementation (Variant B3, the critical path)

- **Read side**: `VCustomSource` wrapping the S3 `ResponseInputStream`, `access=ACCESS_SEQUENTIAL` (critical — tells libvips it may stream forward-only, which is what actually keeps memory low; without it libvips assumes random access and buffers anyway). No `SeekCallback` for the default/streaming path — S3's `ResponseInputStream` isn't seekable anyway, and this is what makes **progressive JPEG force a full internal buffer** (documented libvips limitation, the exact edge case this benchmark should demonstrate, not paper over).
- **Processing**: prefer `VImage.thumbnailSource(...)` over `newFromSource`+`thumbnailImage` where possible — it fuses libjpeg shrink-on-load into the decode, the single biggest structural win over GM. Bleed = `thumbnailImage(crop=CENTRE)` + `embed(bleedPx, bleedPx, fullW, fullH, extend=MIRROR)` — meaningfully simpler than IM's `-virtual-pixel Mirror`/`-distort SRT`/`-extent` chain.
- **Write side**: `VCustomTarget` → `StreamingPartUploadOutputStream`, manual S3 multipart, buffering to a 5MiB (S3's multipart minimum) threshold. **Critical correctness detail**: since resized photobook images (a few hundred KB to a few MB) will almost never cross that threshold, use a **lazy single-PUT fast path** — only initiate a real multipart upload if the buffer actually fills. Without this, every job pays 3 S3 round-trips instead of 1 for reasons that have nothing to do with streaming, and Variant B3 would lose on pure network overhead.
- **Non-negotiable setup**: `Vips.disableOperationCache()` before any drain — libvips caches ~100 operations by default, which would make repeated drains of the same fixture images fraudulently fast.

### Metrics (Linux-only via `/proc`, run only in Docker)

- **Queue drain** (mean±stddev over measured reps, matching `hyperfine`'s statistic), **throughput**, **peak RSS**.
- Peak RSS: track parent-JVM PID + any spawned GM/IM child PIDs separately (`peakSelf`/`peakChildren`/`peakTotal`) — reused `ps -eo rss,comm` regex matching from the bash benchmarks is not precise enough here (double-counts shared pages, matches too broadly); explicit PID tracking via `Process.pid()` plus `/proc/<pid>/status` `VmRSS` sampled every ~20-50ms, using `VmHWM` for the JVM's own exact high-water mark.
- **Per-job latency percentiles** (p50/p95/p99/max) — pool samples across all measured reps for a meaningful sample count, and print the sample count next to them.
- **`overlapRatio = (fetch+process+upload)/total`** — the direct quantitative proof of whether streaming actually overlaps I/O and compute. ≈1.0 for Variant A (phases are genuinely disjoint there); >1.0 for B3 is the headline number.
- **GC** (`GarbageCollectorMXBean` count/pause-time deltas per rep) and **CPU utilization** (JVM via `OperatingSystemMXBean`, GM/IM children via `/proc/<pid>/stat` utime+stime, since `getProcessCpuTime()` only covers the JVM itself and would make Variant A look nearly idle while it's actually pegging every core).
- **Output-correctness gate** (`--verify` mode, run once outside the timed path): assert output dimensions match, quantify perceptual/pixel diff between GM/IM and vips output, and explicitly flag known real divergence sources (GM's `-filter Bessel` vs. vips' default Lanczos3, `RGB` vs `sRGB` colorspace naming inconsistency already present in the real repo's own commands). A performance comparison between pipelines producing meaningfully different images isn't a valid comparison — this gate exists to catch that before trusting any number.

### Network latency injection

MinIO (not LocalStack — LocalStack's own per-request overhead is larger and noisier than the latency being deliberately injected, which would drown the signal; kept available behind a compose profile for a sanity cross-check since it's what the rest of the company already runs locally) + **Toxiproxy always in the request path** (even at zero injected latency, so the proxy hop itself is a constant, not a confound that appears only in latency runs). Latency profiles apply both a `latency` and `bandwidth` toxic together (`none` / same-region `az` / cross-region `region` / `degraded`), plus one explicit direct-to-MinIO control run to quantify Toxiproxy's own overhead. Toxiproxy doesn't model TCP congestion-window growth — treat results as directionally correct for the streaming-vs-buffered **trend** across latency, not as precise real-S3 predictions.

## Project layout

Independent project root at `~/IdeaProjects/libvips-benchmark/` (own git repo):

```
~/IdeaProjects/libvips-benchmark/
  pom.xml                     # Maven — single fat jar via shade plugin, simpler than Gradle for one artifact
  Dockerfile                  # JDK 25 (LTS, FFM final since 22) + IM/GM (same install steps as real Dockerfile) + libvips42/libvips-dev/libglib2.0-0
  docker-compose.yml          # bench, minio, toxiproxy, toxics (one-shot), seed (one-shot)
  samples/                    # baseline.jpg (from sample_original.jpg) + progressive.jpg (convert -interlace JPEG)
  src/main/resources/sRGB.icc # copy of conf/imagemagick/sRGB.icc
  src/main/java/com/journi/bench/
    Main.java, BenchConfig.java
    harness/   DrainHarness.java, WorkQueue.java, JobResult.java
    variant/   Variant.java, GmFetchThenProcessVariant.java, ImBleedFetchThenProcessVariant.java,
               VipsStreamingVariant.java (B0-B3 modes), 
    s3/        S3Support.java, StreamingPartUploadOutputStream.java
    metrics/   RssSampler.java, GcSampler.java, CpuSampler.java, Stats.java, ResultTable.java
```

## Key risks to spike first (before building the harness around them)

1. **Arena confinement**: libvips runs its own worker threadpool internally; `Arena.ofConfined()` throws if touched from a non-owning thread. Use `Arena.ofShared()` per job, not `Vips.run()`'s default confined arena. Spike: one image end-to-end (`newFromSource`→`embed`→`writeToTarget`) with both, confirm shared survives.
2. **GM/IM thread oversubscription**: IM 6.9 may use OpenMP internally (GM typically doesn't). If `convert` silently uses all cores per subprocess, the `cores*2`-concurrent model is oversubscribed by ~8x and invalidates the whole comparison. Verify with `convert -list resource` in the container; pin `OMP_NUM_THREADS=1`/`MAGICK_THREAD_LIMIT=1` if needed.
3. **Progressive JPEG behavior**: confirm it actually forces vips' internal buffering as documented, with a real side-by-side memory/timing comparison against the baseline JPEG — this is a feature to demonstrate, not a bug to hide.

## Build order

1. Spike risks 1-3 above (half a day, blocks everything else).
2. `BenchConfig`/`Main` + MinIO compose + `--seed-bucket` (uploads baseline/progressive under distinct keys).
3. Variant A (GM standard resize) end-to-end, single-threaded, no metrics yet — validate output.
4. `DrainHarness`+`Stats`+text `ResultTable` — cross-check Variant A's number against the earlier `benchmark_queue_drain.sh` bash result as a sanity check that the JVM harness isn't measuring something different.
5. `RssSampler`/`GcSampler`/`CpuSampler`.
6. `VipsStreamingVariant` B0 (buffered/buffered — simplest, no custom callbacks) — gets an early "is libvips itself faster" answer.
7. `StreamingPartUploadOutputStream` + B3, with the single-PUT fast path.
8. B1/B2 (mixed modes), then bleed resize for all variants.
9. `--verify` output-correctness gate.
10. Toxiproxy + latency-profile sweep + progressive-JPEG comparison runs.
11. `--json` export + results manifest (versions, cpuset, queue size, latency profile, fixture sizes) + README.

## Verification (end-to-end)

- `docker compose up` brings up MinIO + Toxiproxy + runs `seed` (fails loudly, not silently, if seeding fails — `depends_on: condition: service_completed_successfully`).
- Run with `docker compose run --rm bench run --platform linux/amd64` using `cpuset` (not `--cpus`) pinned to **distinct physical cores** (verify via `lscpu -e=CPU,CORE` first — both target CPUs are 8C/16T, so this must be confirmed, not assumed).
- Cross-check: Variant A's queue-drain number from this JVM harness should be in the same ballpark as the equivalent `benchmark_queue_drain.sh` bash result on the same hardware — a meaningful divergence there means the harness itself has a bug, not a real finding.
- Run `--verify` before trusting any timing number, on both standard and bleed operations.
- Run the full latency-profile sweep (`none`/`az`/`region`/`degraded`) and confirm the B3-vs-B0 overlap ratio and relative throughput trend as latency increases — this is the actual answer to the question that motivated building this.

## Reference material from the `core` repo (read-only — copied/mirrored into the new independent project, no lasting dependency)

The new project is fully standalone, but the following existing files inform exact values/commands to reproduce (already verified this session, cited with file:line for traceability — nothing here implies the new project imports or depends on `core`):

- `core/service/src/main/scala/com/journi/service/storage/AbstractS3Storage.scala` — verified: `get()` (:138-142) fully buffers, `getStream()` (:144-146) is raw, `put()` (:105-125) uses `RequestBody.fromInputStream` with known length, client setup with `ApacheHttpClient`+`endpointOverride` (:45-80).
- `core/service/src/main/scala/com/journi/service/image/graphicsmagick/cli/GraphicsMagickClient.scala` + `imagemagick/cli/ImageMagickClient.scala` — exact commands/bleed math to mirror (already fully read earlier this session).
- `core/api/app/com/journi/api/controller/AppController.scala:71` — verified `imageThreadPool` sizing convention.
- `core/build.sbt:116` — verified `awsVersion = "2.41.30"`, pin the same in the new project's `pom.xml`.
- `core/Dockerfile` — IM/GM install steps + `policy.xml` patches to carry over verbatim into the new project's own Dockerfile.
- `core/Dockerfile.benchmark`, `core/benchmark_queue_drain.sh` (scratchpad, this session) — output format and `--cpuset-cpus` convention to stay consistent with, for cross-checking results.
- `core/libvips-benchmark/sample_original.jpg` — existing real fixture image (currently untracked in `core`), copy into the new project as its base fixture.