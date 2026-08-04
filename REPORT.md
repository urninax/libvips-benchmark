# Migrating Journi's Image Processing from GraphicsMagick/ImageMagick to libvips

**Status:** Draft — core performance/memory case is proven; S3-streaming case is still being validated
**Scope:** Photobook image pipeline (resize, bleed/print production) currently served by `core`'s `GraphicsMagickClient`/`ImageMagickClient`

---

## 1. Why we're doing this

As part of ongoing efforts to reduce compute cost and improve image-processing
performance, we evaluated whether GraphicsMagick/ImageMagick is still the
right choice for Journi's photobook pipeline. This is a good time to ask that
question: the image-processing service is already being extracted from the
main API into its own service, which means a core dependency like the image
library can be swapped without disrupting the rest of the monorepo (its own
JVM version, its own dependencies, no shared build).

Separately, and for unrelated reasons, the upload architecture is also moving
to presigned direct-to-S3 uploads. Today, `AppController.postUploadPicture`
receives raw file bytes directly in the HTTP request body — the app never has
to fetch from S3 before processing. Once uploads go directly to S3, the
extracted image-processing service will need to fetch the source from S3
before it can do anything — a new I/O dependency the current architecture
doesn't have. Since the library evaluation was already underway, it made
sense to also check whether this new S3 dependency changes the picture: does
*how* an image library reads its input (fetch the whole file first, or begin
processing as bytes arrive) matter once S3 is in the critical path? That
turned out to be a real, answerable question, and is covered separately in
§3.3 below — but it's a secondary angle this investigation picked up along
the way, not the reason the investigation started.

## 2. What we tested and how

Two rounds of benchmarking, both on real target hardware (AMD Ryzen 7 4800H and 7800X3D, matching production's Fargate architecture), not synthetic/emulated environments:

**Round 1 — CLI-level comparison** (`benchmark_resize.sh`, `benchmark_concurrency.sh`, `benchmark_queue_drain.sh`, `benchmark_vips_sweep.sh`): compared `gm convert`/`convert` against the `vips` CLI, replicating the exact commands and quality settings `GraphicsMagickClient`/`ImageMagickClient` actually generate, at production's real resize target (`mobileFullFormat`, ~2MP area-fit) and bleed geometry (A4@300dpi + 3mm mirror-extend bleed). This answered: *is libvips faster, and what deployment/concurrency model gets the most out of it?*

**Round 2 — S3-streaming comparison** (the standalone `libvips-benchmark` project, this repo): a JVM harness using `vips-ffm` (the only mature Java binding exposing true libvips streaming via the Foreign Function & Memory API) against a real GM/IM fetch-then-process baseline, both reading/writing through MinIO with Toxiproxy injecting realistic S3 network latency/bandwidth profiles. This answered: *does streaming directly from S3 into libvips — instead of downloading the whole file first — provide an additional win once the service has to talk to S3 at all, and does that hold up under real network conditions?*

## 3. What we found

### 3.1 libvips is faster than GraphicsMagick/ImageMagick, by a wide and consistent margin

Across every test — synthetic images, real photos, different hardware, CLI-level and JVM-level — the result was directionally the same:

| Operation | GM/IM baseline | vips (best config) | Margin |
|---|---|---|---|
| Standard resize (CLI, real photo, 8-core) | 5.84/s | 41.0/s | **~7x** |
| Bleed resize (CLI, real photo, 8-core) | 0.82/s | 7.17/s | **~8.7x** |
| Standard resize (JVM harness, real hardware) | 8.4/s | 25-26/s | **~3x** |
| Bleed resize (JVM harness, real hardware) | 1.32/s | 10.2-10.3/s | **~7.7x** |
| Peak memory (CLI, standard resize) | 1997MB | 352MB | **~5.7x less** |
| Peak memory (CLI, bleed resize) | 3894MB | 568MB | **~6.9x less** |

This isn't a marginal optimization — it's consistently multiple times faster while using a fraction of the memory, and it held up on the user's own production-equivalent hardware, not just in a synthetic test. Some of this comes from libvips' shrink-on-load JPEG decode (skipping full-resolution decode before downscaling), and some from its structural efficiency, but the net effect is large regardless of cause.

### 3.2 The right deployment model is "many cheap concurrent processes," not "fewer processes with more internal threading"

libvips can parallelize a *single* image's resize/color operations across multiple internal threads — but JPEG decode itself doesn't parallelize (libjpeg is single-threaded), so giving one image lots of threads leaves cores idle during decode. A concurrency sweep across `(worker count × internal threads)` configurations showed the same pattern every time: **many concurrent single-threaded vips processes beat every alternative**, including running one process with all cores devoted to it. The one-process-at-a-time model was consistently the *worst* option tested.

This matters practically: it means adopting vips doesn't require redesigning the deployment architecture. Today's `imageThreadPool` (a fixed pool of `cores × 2` single-threaded worker slots, `AppController.scala:71`) is already the right shape — you'd swap the binary each worker calls, not the concurrency model around it.

### 3.3 The S3-streaming question is still open

This is the part motivated by the upcoming presigned-upload architecture, and it's the least settled finding.

**What we confirmed works:** `vips-ffm` genuinely supports true streaming — reading from an S3 `GetObject` response and writing to an S3 upload without ever fully buffering either side (`VCustomSource`/`VCustomTarget`, `access=ACCESS_SEQUENTIAL`). The harness runs end-to-end with zero failures across all four streaming/buffering combinations (buffered fetch + buffered put, buffered + streamed, streamed + buffered, streamed + streamed), plus the GM/IM baseline, on real hardware.

**What we haven't yet confirmed:** whether streaming actually *helps* under realistic conditions. In the one full run completed against near-zero-latency local storage, all four vips variants performed statistically identically, and the "overlap" metric (a direct measure of whether I/O and compute genuinely ran concurrently) sat at ~1.0 for every variant — including the streamed ones. That's expected at near-zero latency: there's nothing to overlap with when fetching is already instantaneous. The benchmark includes a latency-injection sweep (`./run-sweep.sh`, via Toxiproxy) modeling realistic S3 conditions — same-region warm connection, same-region under concurrent load, cross-region — specifically to test whether the streamed variants pull ahead as latency increases. **That sweep has not yet been run to completion**, so this report cannot yet say whether streaming is worth building into the new service or whether a simpler buffered fetch-then-process (still using vips, just not exploiting its streaming API) captures most of the benefit.

One structural point already clear regardless of the sweep result: photobook-sized outputs (a few hundred KB) never cross S3's 5MB multipart upload threshold, so "streamed put" and "buffered put" behave identically at these sizes — any streaming benefit that does show up will come from the *fetch* side only, not the upload side.

## 4. What actually needs to change

Mapping the earlier codebase investigation onto migration scope:

**In scope — this is what GM/IM currently do, and what vips would need to replicate:**
- Metadata reads (dimensions, EXIF orientation, GPS, camera info)
- Standard resize, in the several geometry modes the app uses (fit-min, fit-max, area-budget fit, crop-and-extend, explicit crop rectangle)
- Bleed resize with mirror-extend padding for print production — vips' `embed(..., extend=MIRROR)` is a direct, simpler equivalent of ImageMagick's `-virtual-pixel Mirror`/`-distort SRT` chain
- All 5 accepted input formats (JPEG, PNG, WebP, HEIC, HEIF) and all 3 output encodings (JPEG, PNG, WebP) — confirmed there's no input-dependent restriction on which outputs get generated, so the format matrix vips needs to cover is the full 5×3, not a smaller subset

**Out of scope — these don't touch GM/IM at all, so they're unaffected by this migration:**
- User-driven rotate/pan/zoom within a frame (a PDFBox transform matrix, not a pixel operation)
- Face-detection-aware auto-crop *decisions* (computed elsewhere; only the resulting crop rectangle reaches the image library)
- Rounded corners, circle masks, watermarking, sticker/overlay compositing (pure Java AWT/PDFBox)
- Brightness/contrast/tone/scene enhancement (delegated entirely to an external ML service over HTTP)

**Known correctness gaps to verify before cutover, not yet automated:**
- GM's `-filter Bessel` resize kernel vs. vips' default Lanczos3 will produce visibly different sharpness — needs a side-by-side comparison, not an assumption they match
- A real inconsistency already exists in the current codebase between GM's `RGB` and IM's `sRGB` colorspace naming for the same logical operation; vips' color handling needs to be checked against both, not just one
- A confirmed `vips-ffm` bug: its `flatten()` background-color option throws on a 3-element RGB vector. Worked around by dropping the call for the (alpha-free) JPEG fixtures tested, but this means alpha-channel sources (PNG with transparency) aren't yet verified to flatten-to-white correctly the way GM's `-background "#ffffff" -flatten` does
- ImageMagick's `policy.xml` provides decompression-bomb protection (hard pixel-dimension/memory caps) that libvips has no direct equivalent for — would need an explicit dimension check added at the application layer if migrating

## 5. Architectural considerations

- **JDK requirement**: `vips-ffm` needs JDK 23+ (the rest of the monorepo runs JDK 21). Low risk specifically *because* the image processor is already being extracted into its own service — it doesn't need to match the main API's JDK version.
- **No backpressure exists today, independent of this migration**: the upload endpoint's worker queue (`FixedThreadPool`, wrapping an unbounded `LinkedBlockingQueue`) has no depth cap or rejection policy, and autoscaling is CPU-reactive only, not queue-aware. This isn't caused by or fixed by the libvips migration, but it's worth flagging now: a presigned-upload architecture could change burst-arrival patterns (uploads no longer rate-limited by the app's own request-handling capacity), and an unbounded queue is exactly the design that handles a burst worst. Worth addressing as related but separate hardening work.
- **Library maturity**: `vips-ffm` is a real, actively-developed library (Apache-2.0, on Maven Central despite a stale search index initially suggesting otherwise) but is young and self-describes as "aiming to be production ready" rather than claiming it already is — smaller community than the Python (`pyvips`) or Go (`govips`) ecosystems. The bug found during this investigation (flatten) is a concrete data point for that maturity gap, not just a theoretical concern.

## 6. Recommendation

**The core performance and memory case is decisive and doesn't depend on resolving the streaming question.** A ~3-9x throughput improvement and ~5-7x memory reduction, on real hardware, with no required change to the existing worker-pool deployment model, justifies migrating the resize/bleed operations to libvips on its own merits — independent of whatever happens with S3 uploads.

**Before finalizing, in priority order:**
1. **Run the latency-injection sweep to completion** (`./run-sweep.sh`) — this determines whether the new service should be architected around true S3 streaming or can use a simpler buffered fetch-then-process (which still gets the full library-level win, just not the additional I/O-overlap win, if any).
2. **Add an HEIC fixture to the benchmark** — not yet tested, and HEIC is real production traffic (a meaningful fraction of phone-camera uploads), with its own decode characteristics that could behave differently from the JPEG fixtures tested so far.
3. **Build the output-correctness verification pass** (dimension match + perceptual diff) before trusting any further timing numbers, given the known Bessel/Lanczos3 and colorspace-naming discrepancies above.
4. **File or work around the `vips-ffm` flatten bug** upstream, and verify alpha-channel (PNG) flatten-to-white behavior explicitly, since that's a real, confirmed gap, not a hypothetical one.
5. **Treat the backpressure gap as separate follow-up work**, not blocking this migration, but worth scheduling given the upcoming upload-architecture change.
