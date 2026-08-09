package com.journi.bench.variant;

import app.photofox.vipsffm.*;
import app.photofox.vipsffm.enums.VipsAccess;
import app.photofox.vipsffm.enums.VipsExtend;
import app.photofox.vipsffm.enums.VipsFailOn;
import app.photofox.vipsffm.enums.VipsInteresting;
import app.photofox.vipsffm.enums.VipsSize;
import com.journi.bench.BenchConfig;
import com.journi.bench.ImageFormat;
import com.journi.bench.harness.JobResult;
import com.journi.bench.s3.StreamingPartUploadOutputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Variant B: vips-ffm. Implements the full B0-B3 grid via a Mode enum, so the
 * comparison can isolate "is libvips itself faster" (B0 vs GM/IM) from
 * "does streaming actually help" (B3 vs B0) - see plan's variant grid.
 *
 * NOTE ON API SURFACE: exact vips-ffm method signatures (VImage.embed,
 * thumbnailSource, VipsOption factories, VCustomSource/VCustomTarget
 * constructors) were verified by reading the library's source directly
 * during planning, but not compile-checked locally (no JDK23+/Maven
 * available in the authoring environment). First `mvn compile` on the
 * target machine is expected to surface any final signature corrections -
 * treat this file as the spike, not as pre-verified.
 * <p>
 * access=ACCESS_SEQUENTIAL is the single most important option here - it
 * tells libvips it may stream forward-only and never revisit, which is what
 * actually keeps memory low. Without it libvips assumes random access and
 * buffers regardless of the source type.
 */
public final class VipsStreamingVariant implements Variant {

    public enum Mode {B0_BUFF_BUFF, B1_BUFF_STR, B2_STR_BUFF, B3_STR_STR}
    public enum Operation { STANDARD_RESIZE, BLEED_RESIZE }

    private static final long TARGET_PIXELS = 2_250_000L;
    // Bleed content is PictureFormat.originalFormat - an area-fit resize of the SOURCE's own
    // aspect ratio, capped at 16MP - NOT a fixed page size. See bleedResize()'s doc.
    private static final long BLEED_CONTENT_TARGET_PIXELS = 16_777_216L;
    private static final int BLEED_PX = 36;
    private static final int READ_SCRATCH_SIZE = 256 * 1024;
    // Instance state, NOT static: every variant in a sweep shares one classloader, so a static
    // flag written from an instance setUp() is cross-variant global state, and the hot read
    // callback would unbox a null Boolean into an NPE for any variant whose setUp() never ran.
    private boolean readCallbackDebug;

    private final S3Client s3;
    private final String bucket;
    private final Mode mode;
    private final Operation operation;
    private final long partSizeBytes;
    private final String iccProfilePath;
    private final ImageFormat inputFormat;
    private final ImageFormat outputFormat;

    public VipsStreamingVariant(S3Client s3, String bucket, Mode mode, Operation operation,
                                 long partSizeBytes, String iccProfilePath,
                                 ImageFormat inputFormat, ImageFormat outputFormat) {
        this.s3 = s3;
        this.bucket = bucket;
        this.mode = mode;
        this.operation = operation;
        this.partSizeBytes = partSizeBytes;
        this.iccProfilePath = iccProfilePath;
        this.inputFormat = inputFormat;
        this.outputFormat = outputFormat;
    }

    @Override
    public String name() {
        return "vips " + mode.name().substring(0, 2).toLowerCase() + " " + operation.name().toLowerCase()
            + " (" + (mode == Mode.B0_BUFF_BUFF ? "buff/buff" :
                      mode == Mode.B1_BUFF_STR ? "buff/str" :
                      mode == Mode.B2_STR_BUFF ? "str/buff" : "str/str") + ")"
            + " [" + inputFormat.ext + "->" + outputFormat.ext + "]";
    }

    @Override
    public void setUp(BenchConfig cfg) {
        Vips.init();
        this.readCallbackDebug = cfg.readCallbackDebug();
        // NON-NEGOTIABLE: libvips caches ~100 operations by default, which
        // would make repeated drains of the same fixture images fraudulently
        // fast. Disabling this also better models a real long-running server.
        Vips.disableOperationCache();
    }

    @Override
    public void tearDown() {}

    @Override
    public JobResult process(String sourceKey, String destKey) {
        long t0 = System.nanoTime();
        long fetchNanos = 0, uploadNanos = 0;
        long bytesIn = 0, bytesOut = 0;
        int outW = 0, outH = 0;

        try (Arena arena = Arena.ofShared()) { // shared, NOT confined - libvips' own worker
                                                 // threadpool may invoke our callbacks from a
                                                 // thread other than the one that created the
                                                 // arena; confined arenas throw in that case.
            boolean streamFetch = mode == Mode.B2_STR_BUFF || mode == Mode.B3_STR_STR;
            boolean streamPut = mode == Mode.B1_BUFF_STR || mode == Mode.B3_STR_STR;

            VSource vsrc = null;
            byte[] bufferedInput = null;
            // Fetch is timed as a wall-clock SPAN (first byte requested -> last byte handed to
            // libvips), NOT as a sum of intervals spent blocked inside read(). Summing blocked
            // reads made overlapRatio structurally incapable of measuring the thing it exists to
            // measure: once the kernel socket buffer holds the response, read() returns
            // immediately, so an object that spent 115ms on the wire concurrently with decoding
            // registered as ~0ms of "fetch" and the ratio sat at 1.00-1.01 even on runs where
            // streaming was demonstrably saving ~100ms/job (visible only as higher cpu%). The
            // buffered branch's fetch is already a contiguous span, so both branches now measure
            // the same quantity - and a span that extends past procStart is exactly what makes
            // the fetch+process+upload sum exceed total, i.e. real overlap.
            long fetchSpanStart = System.nanoTime();
            AtomicLong lastReadEndNanos = new AtomicLong(0);
            AtomicLong bytesReadAcc = new AtomicLong(0);
            if (streamFetch) {
                var getResponse = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(sourceKey).build());
                // getObject() blocks for the connection + response-header round-trip before
                // returning - real fetch cost (one latency hop), and part of the span above.
                lastReadEndNanos.set(System.nanoTime());
                vsrc = new VCustomSource(arena, readCallback(getResponse, lastReadEndNanos, bytesReadAcc));
                // The read callback only closes the S3 response stream when it observes EOF
                // (n <= 0). libvips isn't guaranteed to read a source down to true EOF (e.g. it
                // may stop once it has enough data to decode/thumbnail), which would otherwise
                // leak the underlying pooled HTTP connection on every such job - exhausting the
                // shared S3Client's connection pool after enough reps. Registering this cleanup
                // on the arena (same idiom as VSource#newFromInputStream) guarantees the stream
                // closes exactly once when this job's arena scope ends, success or failure.
                vsrc.getUnsafeStructAddress().reinterpret(arena, (_) -> {
                    try {
                        getResponse.close();
                    } catch (IOException ignored) {
                    }
                });
            } else {
                try (InputStream in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(sourceKey).build())) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    in.transferTo(buf);
                    bufferedInput = buf.toByteArray();
                }
                fetchNanos = System.nanoTime() - fetchSpanStart;
                bytesIn = bufferedInput.length;
            }

            long procStart = System.nanoTime();
            VImage processed = operation == Operation.STANDARD_RESIZE
                ? standardResize(arena, vsrc, bufferedInput)
                : bleedResize(arena, vsrc, bufferedInput);
            processed = flattenIfNeeded(processed);
            long procEnd = System.nanoTime();
            outW = (int) processed.getWidth();
            outH = (int) processed.getHeight();

            if (streamFetch) {
                // Close the span at the last read the loader actually performed. Deliberately not
                // procEnd: libvips may stop reading well before it finishes computing, and
                // charging the remaining compute time to "fetch" would manufacture overlap that
                // isn't there.
                fetchNanos = Math.max(0, lastReadEndNanos.get() - fetchSpanStart);
                bytesIn = bytesReadAcc.get();
            }

            if (streamPut) {
                long us = System.nanoTime();
                try (StreamingPartUploadOutputStream upload =
                         new StreamingPartUploadOutputStream(s3, bucket, destKey, partSizeBytes)) {
                    VTarget vtgt = new VCustomTarget(arena, writeCallback(upload), endCallback(upload));
                    saveToTarget(processed, vtgt);
                    bytesOut = upload.totalBytesWritten();
                }
                uploadNanos = System.nanoTime() - us;
            } else {
                long us = System.nanoTime();
                VTarget memTarget = VTarget.newToMemory(arena);
                saveToTarget(processed, memTarget);
                byte[] outBytes = memTarget.getBlob().getBytes();
                bytesOut = outBytes.length;
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(destKey).contentLength((long) outBytes.length).build(),
                    RequestBody.fromBytes(outBytes));
                uploadNanos = System.nanoTime() - us;
            }

            long total = System.nanoTime() - t0;
            return new JobResult(sourceKey, true, null, total,
                fetchNanos, procEnd - procStart, uploadNanos,
                bytesIn, bytesOut, outW, outH);
        } catch (Exception e) {
            return JobResult.failure(sourceKey, e.toString(), System.nanoTime() - t0);
        }
    }

    /**
     * Composites any alpha channel onto white when saving to a format that can't carry
     * alpha (JPEG) - a no-op for opaque sources or alpha-capable output formats (PNG/WebP).
     * <p>
     * NOTE ON THE PREVIOUSLY-REPORTED flatten() BUG: calling
     * .flatten(VipsOption.ArrayDouble("background", [255,255,255])) on a NON-alpha image
     * reproducibly throws "linear: vector must have 1 or 2 elements" - confirmed via the
     * `vips` CLI directly, independent of vips-ffm, so it's a real libvips quirk (flatten
     * on an image with no alpha band apparently routes the background vector through a
     * path that only accepts 1-2 elements). It is NOT a bug in flatten() on real
     * alpha-channel input - verified empirically against an actual alpha PNG, where the
     * exact same call succeeds with no error. The original investigation only ever tested
     * flat opaque JPEG fixtures, so it never actually exercised flatten()'s real path -
     * the fix is simply to never call it unless the image actually has an alpha channel
     * to flatten, which is also the only case where calling it makes sense.
     */
    private VImage flattenIfNeeded(VImage image) throws VipsError {
        if (!outputFormat.supportsAlpha && image.hasAlpha()) {
            return image.flatten(VipsOption.ArrayDouble("background", List.of(255.0, 255.0, 255.0)));
        }
        return image;
    }

    private void saveToTarget(VImage image, VTarget target) throws VipsError {
        switch (outputFormat) {
            case JPG -> image.jpegsaveTarget(target, VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true));
            // filter=248 is libpng's PNG_ALL_FILTERS (0xF8), the adaptive per-row filtering
            // GM does via `-define png:filter-type=6`. pngsave defaults to filter=none, which
            // silently emits a ~28% larger file in a fraction of the time (see
            // LocalResizeBenchmark's matching fix) - without this, GM vs vips isn't comparing
            // resize speed, it's comparing "did adaptive filtering or not".
            case PNG -> image.pngsaveTarget(target, VipsOption.Boolean("strip", true),
                VipsOption.Int("filter", 248));
            case WEBP -> image.webpsaveTarget(target, VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true));
        }
    }

    // thumbnailBuffer/thumbnailSource fuse libjpeg shrink-on-load into the decode -
    // the largest single structural win over GM, since it avoids ever materializing
    // a full-resolution decoded raster for a downscale. VImage#thumbnailImage (an
    // instance method operating on an ALREADY-loaded VImage) explicitly does NOT
    // get this: its own doc says "not able to exploit shrink-on-load features of
    // image load libraries, so it can be much slower...only use this operation if
    // you really have to." The original version of this file loaded via
    // newFromSource() and then called thumbnailImage() on the result for every
    // mode, which silently forfeited shrink-on-load everywhere - including "b2"/"b3"
    // streamed-fetch, defeating the entire point of the streamed variants: bytes
    // could never be decoded progressively as they arrived, because the loader
    // node was already committed to a full-resolution decode before any target
    // size was known.

    private VImage standardResize(Arena arena, VSource vsrc, byte[] bufferedInput) throws VipsError {
        // NOTE: GM's command chain includes `-background "#ffffff" -flatten` to
        // composite any alpha channel onto white before JPEG encode. The equivalent
        // here is applied AFTER resize, in flattenIfNeeded() (called from process()) -
        // see that method's doc for why flatten() is only called when the image
        // actually has an alpha channel (calling it unconditionally on an opaque
        // source throws, which is what an earlier version of this comment
        // misattributed to a vips-ffm bug).
        if (bufferedInput != null) {
            // Bytes are already fully in memory, so a second, throwaway header-only
            // load (just to learn width/height for areaFit) costs nothing extra -
            // the real work below still gets a fresh loader built with the shrink
            // target already known, so thumbnailBuffer can shrink-on-load.
            VImage peek = VImage.newFromSource(arena, VSource.newFromBytes(arena, bufferedInput),
                VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL));
            int[] target = GmStandardVariant.areaFit((int) peek.getWidth(), (int) peek.getHeight(), TARGET_PIXELS);
            return VImage.thumbnailBuffer(arena, VBlob.newFromBytes(arena, bufferedInput), target[0],
                VipsOption.Int("height", target[1]),
                VipsOption.Enum("size", VipsSize.SIZE_FORCE),
                VipsOption.String("export-profile", "srgb"),
                VipsOption.String("import-profile", iccProfilePath));
        }

        // Streamed fetch: area-fit needs the source's real width/height before a
        // shrink-on-load decode can be planned, but a live, single-pass network
        // stream can't be read twice - peeking the header and later reading pixels
        // would both have to pull from the same vsrc/loader node. So this path
        // can't get thumbnail_source's shrink-on-load fusion without a second,
        // separate range-GET purely to sniff dimensions (out of scope here) -
        // left as the same non-shrink-on-load thumbnailImage() path as before,
        // called out explicitly rather than silently left half-fixed.
        VImage source = VImage.newFromSource(arena, vsrc,
            VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL),
            VipsOption.Enum("fail-on", VipsFailOn.FAIL_ON_ERROR));
        int[] target = GmStandardVariant.areaFit((int) source.getWidth(), (int) source.getHeight(), TARGET_PIXELS);
        // ICC AFTER the resize, not before - see iccAfterResize()'s doc. This branch used to
        // colour-transform at full source resolution, which is 4x the pixels of the 2.25MP output
        // and was single-handedly responsible for streamed fetch losing this operation.
        return iccAfterResize(source
            .autorot()
            .thumbnailImage(
                target[0],
                VipsOption.Int("height", target[1]),
                VipsOption.Enum("size", VipsSize.SIZE_FORCE)));
    }

    /**
     * Colour-manages to sRGB AFTER the resize has already reduced the pixel count.
     * <p>
     * This is what libvips' own thumbnail does internally: the buffered branches pass
     * import-profile/export-profile to thumbnailBuffer, and thumbnail applies colour management
     * downstream of its resize, so it only ever transforms output-sized pixels. The streamed
     * branches previously called iccTransform() on the freshly-loaded, full-resolution source
     * before handing it to thumbnailImage, so they paid the transform at source resolution.
     * <p>
     * Measured on the 2268x4032 (9.14MP) fixtures, purely in-memory with no network involved:
     * standard resize 153.2ms -> 77.3ms (buffered thumbnailBuffer is 78.1ms, i.e. exact parity),
     * bleed 124.1ms -> 112.0ms. The reorder is pixel-identical - max absolute channel difference
     * between the two orderings is 0.0 - so this is free.
     * <p>
     * The asymmetry this explains: bleed's content target is areaFit(source, 16MP), and a 9.14MP
     * source clamps that scale to 1.0, so content dimensions EQUAL source dimensions and both
     * orderings transformed the same 9.14MP either way (~0 delta). Standard resize outputs 2.25MP,
     * so the mis-ordering cost 4x there. That, not streaming, was the whole difference between
     * "streaming helps bleed" and "streaming hurts standard resize".
     */
    private VImage iccAfterResize(VImage resized) throws VipsError {
        return resized.iccTransform("srgb", VipsOption.String("input-profile", iccProfilePath));
    }

    /**
     * Content dimensions are an AREA-FIT of the SOURCE image's own aspect ratio (capped at 16MP,
     * PictureFormat.originalFormat) - NOT a fixed page size. Confirmed against
     * benchmark_resize.sh (generated from the real ImageMagickClient.scala/PictureFormat.scala,
     * see ImBleedVariant's doc) and LocalResizeBenchmark.java's bleed case, which already computed
     * this correctly. A prior version of this method hardcoded content at a fixed A4@300dpi guess,
     * independent of the source - wrong, and it also silently hid the real cost of this operation:
     * because that fixed target needed no source dimensions, BOTH fetch modes could reach the
     * shrink-on-load thumbnail entry points for free. The real area-fit target can't be known
     * until the source's actual width/height are read, which reproduces standardResize()'s own
     * peek-vs-stream fork below - streamed fetch loses shrink-on-load here too, for the same
     * reason it does there.
     */
    private VImage bleedResize(Arena arena, VSource vsrc, byte[] bufferedInput) throws VipsError {
        if (bufferedInput != null) {
            // Bytes already fully in memory, so a throwaway header-only peek costs nothing - the
            // real work below still gets a fresh loader built with the shrink target known, so
            // thumbnailBuffer can shrink-on-load. Mirrors standardResize()'s buffered path.
            VImage peek = VImage.newFromSource(arena, VSource.newFromBytes(arena, bufferedInput),
                VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL));
            int[] content = GmStandardVariant.areaFit((int) peek.getWidth(), (int) peek.getHeight(), BLEED_CONTENT_TARGET_PIXELS);
            int fullW = content[0] + 2 * BLEED_PX, fullH = content[1] + 2 * BLEED_PX;
            VImage contentImg = VImage.thumbnailBuffer(arena, VBlob.newFromBytes(arena, bufferedInput), content[0],
                VipsOption.Int("height", content[1]),
                VipsOption.Enum("crop", VipsInteresting.INTERESTING_CENTRE),
                VipsOption.String("export-profile", "srgb"),
                VipsOption.String("import-profile", iccProfilePath));
            return contentImg.embed(BLEED_PX, BLEED_PX, fullW, fullH,
                VipsOption.Enum("extend", VipsExtend.EXTEND_MIRROR));
        }

        // Streamed fetch: a live, single-pass network stream can't be read twice - peeking the
        // header to compute the area-fit content target and later reading pixels would both have
        // to pull from the same vsrc/loader node. Same non-shrink-on-load thumbnailImage()
        // fallback as standardResize()'s streamed branch, for the same reason.
        VImage source = VImage.newFromSource(arena, vsrc,
            VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL),
            VipsOption.Enum("fail-on", VipsFailOn.FAIL_ON_ERROR));
        int[] content = GmStandardVariant.areaFit((int) source.getWidth(), (int) source.getHeight(), BLEED_CONTENT_TARGET_PIXELS);
        int fullW = content[0] + 2 * BLEED_PX, fullH = content[1] + 2 * BLEED_PX;
        // ICC after the resize and before the embed - matching the buffered branch above, which
        // gets its colour management inside thumbnailBuffer (i.e. post-resize) and only then
        // mirrors the bleed border out of already-transformed pixels. See iccAfterResize()'s doc.
        VImage contentImg = iccAfterResize(source
            .autorot()
            .thumbnailImage(content[0],
                VipsOption.Int("height", content[1]),
                VipsOption.Enum("crop", VipsInteresting.INTERESTING_CENTRE)));
        return contentImg.embed(BLEED_PX, BLEED_PX, fullW, fullH,
            VipsOption.Enum("extend", VipsExtend.EXTEND_MIRROR));
    }

    /**
     * Reusable per-job scratch buffer, NOT the library's default readNBytes-per-
     * call implementation (which allocates a fresh byte[] on every callback -
     * hundreds of short-lived arrays per image, times cores*2 jobs, times
     * measured reps - exactly the GC noise the GcSampler metric is meant to
     * surface). single read(), not readNBytes(): 0 return means EOF to libvips,
     * -1 means error - NOT the same as a short read.
     * <p>
     * Records the wall-clock instant each read COMPLETED (rather than accumulating time spent
     * blocked inside read()) and the running byte count, so process() can close out the fetch span
     * and report a real bytesIn for the streamed branches - see the span comment in process().
     */
    private VCustomSource.ReadCallback readCallback(InputStream in, AtomicLong lastReadEndNanos,
                                                    AtomicLong bytesReadAcc) {
        byte[] scratch = new byte[READ_SCRATCH_SIZE];
        AtomicInteger callCount = new AtomicInteger(0);
        boolean debug = readCallbackDebug;
        return (MemorySegment dataPointer, long length) -> {
            int call = callCount.incrementAndGet();
            if (debug && (call % 10 == 0)) System.err.println("[callback] read call #" + call + ", want " + length + " bytes");
            try {
                int want = (int) Math.min(length, scratch.length);
                int n = in.read(scratch, 0, want);
                if (n <= 0) {
                    try { in.close(); } catch (IOException ignored) {}
                    return 0; // EOF
                }
                MemorySegment.copy(scratch, 0, dataPointer, ValueLayout.JAVA_BYTE, 0L, n);
                bytesReadAcc.addAndGet(n);
                return (long) n;
            } catch (IOException e) {
                return -1L;
            } finally {
                long now = System.nanoTime();
                // Monotonic max, not a bare set: libvips may invoke this from more than one of its
                // own worker threads, and a straggler finishing out of order must not rewind the span.
                lastReadEndNanos.updateAndGet(prev -> Math.max(prev, now));
            }
        };
    }

    // Both callbacks catch Throwable, NOT IOException. writeSegment()/finish() are declared
    // "throws IOException" but that's not enforced for unchecked exceptions - s3.putObject()/
    // uploadPart() throw S3Exception (RuntimeException -> SdkException -> AwsServiceException),
    // which sails straight past a narrower catch. Confirmed for real: a MinIO "no space left on
    // device" mid-sweep threw S3Exception out of finish(), past this catch, through the native
    // FFM callback boundary, and took the whole JVM down ("Unrecoverable uncaught exception
    // encountered. The VM will now exit") - every other concurrent job in that process died with
    // it, not just the one that hit the S3 error. Only the STREAMED-PUT variants (b1/b3) are
    // exposed to this; buffered-put routes through process()'s own catch (Exception e), which
    // already catches S3Exception.
    private static VCustomTarget.WriteCallback writeCallback(StreamingPartUploadOutputStream upload) {
        return (MemorySegment seg) -> {
            try {
                return upload.writeSegment(seg);
            } catch (Throwable t) {
                return -1L;
            }
        };
    }

    private static VCustomTarget.EndCallback endCallback(StreamingPartUploadOutputStream upload) {
        return () -> {
            try {
                upload.finish();
                return 0;
            } catch (Throwable t) {
                return -1;
            }
        };
    }
}
