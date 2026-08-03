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
import java.util.concurrent.atomic.AtomicReference;

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
    private static final int CONTENT_W = 2480, CONTENT_H = 3508, BLEED_PX = 36;
    private static final int FULL_W = CONTENT_W + 2 * BLEED_PX, FULL_H = CONTENT_H + 2 * BLEED_PX;
    private static final int READ_SCRATCH_SIZE = 256 * 1024;
    private static Boolean readCallbackDebug;

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
        AtomicReference<Long> fetchNanos = new AtomicReference<>(0L);
        AtomicReference<Long> uploadNanos = new AtomicReference<>(0L);
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
            if (streamFetch) {
                long fs = System.nanoTime();
                var getResponse = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(sourceKey).build());
                // getObject() blocks for the connection + response-header round-trip before
                // returning - that's real fetch cost (one latency hop), but was previously
                // invisible to fetchNanos, which only started accumulating time inside the
                // read callback below. Buffered fetch times its "fetch" as one continuous span
                // starting here (see the else branch), so credit this span the same way -
                // otherwise streamed fetch silently understates its own cost as pure latency
                // grows, which pulls the overlap ratio below 1.0 even when genuine I/O/compute
                // overlap is happening (visible instead in higher CPU utilization).
                fetchNanos.set(System.nanoTime() - fs);
                vsrc = new VCustomSource(arena, readCallback(getResponse, fetchNanos));
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
                long fs = System.nanoTime();
                try (InputStream in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(sourceKey).build())) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    in.transferTo(buf);
                    bufferedInput = buf.toByteArray();
                }
                fetchNanos.set(System.nanoTime() - fs);
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

            if (streamPut) {
                long us = System.nanoTime();
                try (StreamingPartUploadOutputStream upload =
                         new StreamingPartUploadOutputStream(s3, bucket, destKey, partSizeBytes)) {
                    VTarget vtgt = new VCustomTarget(arena, writeCallback(upload), endCallback(upload));
                    saveToTarget(processed, vtgt);
                    bytesOut = upload.totalBytesWritten();
                }
                uploadNanos.set(System.nanoTime() - us);
            } else {
                long us = System.nanoTime();
                VTarget memTarget = VTarget.newToMemory(arena);
                saveToTarget(processed, memTarget);
                byte[] outBytes = memTarget.getBlob().getBytes();
                bytesOut = outBytes.length;
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(destKey).contentLength((long) outBytes.length).build(),
                    RequestBody.fromBytes(outBytes));
                uploadNanos.set(System.nanoTime() - us);
            }

            long total = System.nanoTime() - t0;
            return new JobResult(sourceKey, true, null, total,
                fetchNanos.get(), procEnd - procStart, uploadNanos.get(),
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
            case PNG -> image.pngsaveTarget(target, VipsOption.Boolean("strip", true));
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
        return source
            .autorot()
            .iccTransform("srgb", VipsOption.String("input-profile", iccProfilePath))
            .thumbnailImage(
                target[0],
                VipsOption.Int("height", target[1]),
                VipsOption.Enum("size", VipsSize.SIZE_FORCE));
    }

    private VImage bleedResize(Arena arena, VSource vsrc, byte[] bufferedInput) throws VipsError {
        // CONTENT_W/CONTENT_H are fixed constants (unlike standardResize's
        // area-fit target), so this needs no dimension peek at all - both fetch
        // modes can go straight to the shrink-on-load-capable thumbnail entry
        // point.
        VImage content = bufferedInput != null
            ? VImage.thumbnailBuffer(arena, VBlob.newFromBytes(arena, bufferedInput), CONTENT_W,
                VipsOption.Int("height", CONTENT_H),
                VipsOption.Enum("crop", VipsInteresting.INTERESTING_CENTRE),
                VipsOption.String("export-profile", "srgb"),
                VipsOption.String("import-profile", iccProfilePath))
            : VImage.thumbnailSource(arena, vsrc, CONTENT_W,
                VipsOption.Int("height", CONTENT_H),
                VipsOption.Enum("crop", VipsInteresting.INTERESTING_CENTRE),
                VipsOption.String("export-profile", "srgb"),
                VipsOption.String("import-profile", iccProfilePath));
        return content.embed(BLEED_PX, BLEED_PX, FULL_W, FULL_H,
            VipsOption.Enum("extend", VipsExtend.EXTEND_MIRROR));
    }

    /**
     * Reusable per-job scratch buffer, NOT the library's default readNBytes-per-
     * call implementation (which allocates a fresh byte[] on every callback -
     * hundreds of short-lived arrays per image, times cores*2 jobs, times
     * measured reps - exactly the GC noise the GcSampler metric is meant to
     * surface). single read(), not readNBytes(): 0 return means EOF to libvips,
     * -1 means error - NOT the same as a short read.
     */
    private static VCustomSource.ReadCallback readCallback(InputStream in, AtomicReference<Long> fetchNanosAcc) {
        byte[] scratch = new byte[READ_SCRATCH_SIZE];
        AtomicInteger callCount = new AtomicInteger(0);
        return (MemorySegment dataPointer, long length) -> {
            int call = callCount.incrementAndGet();
            if (readCallbackDebug && (call % 10 == 0)) System.err.println("[callback] read call #" + call + ", want " + length + " bytes");
            long ioStart = System.nanoTime();
            try {
                int want = (int) Math.min(length, scratch.length);
                int n = in.read(scratch, 0, want);
                if (n <= 0) {
                    try { in.close(); } catch (IOException ignored) {}
                    return 0; // EOF
                }
                MemorySegment.copy(scratch, 0, dataPointer, ValueLayout.JAVA_BYTE, 0L, n);
                return (long) n;
            } catch (IOException e) {
                return -1L;
            } finally {
                fetchNanosAcc.updateAndGet(v -> v + (System.nanoTime() - ioStart));
            }
        };
    }

    private static VCustomTarget.WriteCallback writeCallback(StreamingPartUploadOutputStream upload) {
        return (MemorySegment seg) -> {
            try {
                return upload.writeSegment(seg);
            } catch (IOException e) {
                return -1L;
            }
        };
    }

    private static VCustomTarget.EndCallback endCallback(StreamingPartUploadOutputStream upload) {
        return () -> {
            try {
                upload.finish();
                return 0;
            } catch (IOException e) {
                return -1;
            }
        };
    }
}
