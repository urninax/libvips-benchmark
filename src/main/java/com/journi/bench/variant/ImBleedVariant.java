package com.journi.bench.variant;

import com.journi.bench.BenchConfig;
import com.journi.bench.ImageFormat;
import com.journi.bench.harness.JobResult;
import com.journi.bench.metrics.RssSampler;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Variant A (bleed): ImageMagick fetch-then-process, mirroring
 * ImageMagickClient.buildCmd + bleedOperation exactly. Content is
 * PictureFormat.originalFormat - an AREA-FIT resize preserving the SOURCE
 * image's own aspect ratio, capped at 16 megapixels (4096x4096) - not a fixed
 * page size. Bleed is a fixed 36px/edge (3mm @ 300dpi, ProductConstants.
 * DEFAULT_BLEED) mirror-extend border added around that source-dependent
 * content size.
 *
 * PREVIOUSLY WRONG: this hardcoded content at 2480x3508 (an A4@300dpi
 * guess), independent of the source image entirely. benchmark_resize.sh -
 * generated from the real ImageMagickClient.scala/PictureFormat.scala - shows
 * the real bleed operation computes content as area_fit(16MP) against the
 * SOURCE's actual width/height (its "ORIG" target), then adds the bleed
 * border around THAT. LocalResizeBenchmark.java's bleed case already does
 * this correctly (via GmStandardVariant.areaFit(srcW, srcH, 16_777_216L)) -
 * this variant just hadn't been brought in line with it.
 */
public final class ImBleedVariant implements Variant {

    private static final long CONTENT_TARGET_PIXELS = 16_777_216L; // PictureFormat.originalFormat, 4096x4096 cap
    private static final int BLEED_PX = 36;
    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    private final S3Client s3;
    private final String bucket;
    private final Path iccPath;
    private final Path workDir;
    private final RssSampler rssSampler;
    private final ImageFormat inputFormat;
    private final ImageFormat outputFormat;

    public ImBleedVariant(S3Client s3, String bucket, Path iccPath, Path workDir, RssSampler rssSampler,
                           ImageFormat inputFormat, ImageFormat outputFormat) {
        this.s3 = s3;
        this.bucket = bucket;
        this.iccPath = iccPath;
        this.workDir = workDir;
        this.rssSampler = rssSampler;
        this.inputFormat = inputFormat;
        this.outputFormat = outputFormat;
    }

    @Override
    public String name() {
        return "IM bleed resize (fet-proc) [" + inputFormat.ext + "->" + outputFormat.ext + "]";
    }

    /**
     * Deliberately NOT cfg.workers() - see GmStandardVariant.workerCount()'s
     * comment. IM's per-process footprint (full separate OS process per job)
     * matters even more here than for standard resize: bleed's uncompressed
     * working set is larger, and this variant alone was already at ~8GB total
     * RSS at only 16 concurrent workers in earlier runs. Capped at cores*2
     * regardless of how high BENCH_WORKERS goes for vips' own scaling tests.
     */
    @Override
    public int workerCount(BenchConfig cfg) {
        return Math.min(cfg.workers(), cfg.cores() * 2);
    }

    @Override
    public void setUp(BenchConfig cfg) throws Exception {
        Files.createDirectories(workDir);
    }

    @Override
    public void tearDown() {}

    @Override
    public JobResult process(String sourceKey, String destKey) {
        long t0 = System.nanoTime();
        Path inFile = null, outFile = null;
        try {
            long fetchStart = System.nanoTime();
            byte[] full = fetchFully(sourceKey);
            inFile = ProcessRunner.tempFile(workDir, "im-in-", "." + inputFormat.ext);
            Files.write(inFile, full);
            long fetchEnd = System.nanoTime();

            // NOTE ON THE PROCESS METRIC: identifyDims() is a second `gm identify` fork (GM's
            // binary, not IM's - matches GmStandardVariant, and confirmed interchangeable for a
            // pure dimension read), timed inside procStart/procEnd alongside the resize itself -
            // same asymmetry as GmStandardVariant's own identify call, and for the same reason:
            // the real bleedOperation needs the source's actual width/height to compute its
            // area-fit content target BEFORE it can build the -resize geometry, so this isn't
            // optional overhead, it's a real cost the production command pays too.
            long procStart = System.nanoTime();
            int[] srcDims = GmStandardVariant.identifyDims(inFile);
            int[] content = GmStandardVariant.areaFit(srcDims[0], srcDims[1], CONTENT_TARGET_PIXELS);
            int fullW = content[0] + 2 * BLEED_PX;
            int fullH = content[1] + 2 * BLEED_PX;
            outFile = ProcessRunner.tempFile(workDir, "im-out-", "." + outputFormat.ext);
            ProcessRunner.run(bleedCommand(inFile, outFile, content[0], content[1], fullW, fullH), rssSampler, PROCESS_TIMEOUT_SECONDS);
            long procEnd = System.nanoTime();

            long uploadStart = System.nanoTime();
            long outLen = Files.size(outFile);
            try (InputStream out = Files.newInputStream(outFile)) {
                s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(destKey).contentLength(outLen).build(),
                    RequestBody.fromInputStream(out, outLen));
            }
            long uploadEnd = System.nanoTime();

            return new JobResult(sourceKey, true, null,
                uploadEnd - t0,
                fetchEnd - fetchStart,
                procEnd - procStart,
                uploadEnd - uploadStart,
                full.length, outLen, fullW, fullH);
        } catch (Exception e) {
            return JobResult.failure(sourceKey, e.toString(), System.nanoTime() - t0);
        } finally {
            deleteQuietly(inFile);
            deleteQuietly(outFile);
        }
    }

    private byte[] fetchFully(String key) throws IOException {
        try (InputStream in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            return buf.toByteArray();
        }
    }

    private List<String> bleedCommand(Path in, Path out, int contentW, int contentH, int fullW, int fullH) {
        // Plain concatenation, NOT String.format - "%" here is IM's literal fx-expression
        // syntax and must NOT be doubled to "%%" (that would only be needed inside a
        // format string, which this isn't - single '%' is correct on the raw argv element).
        String viewport = "%[fx:w+" + (2 * BLEED_PX) + "]x%[fx:h+" + (2 * BLEED_PX) + "]-" + BLEED_PX + "-" + BLEED_PX;
        List<String> cmd = new ArrayList<>(List.of("convert",
            "-size", fullW + "x" + fullH, in.toString(),
            "-auto-orient",
            "-profile", iccPath.toString(),
            "-strip", "-colorspace", "sRGB"));
        if (outputFormat.supportsQuality) {
            cmd.addAll(List.of("-quality", "85"));
        }
        if (outputFormat.supportsAlpha) {
            // Preserve alpha - "-background none" is required so the later -extent (which fills
            // the bleed border) fills with transparent rather than opaque, which would otherwise
            // silently flatten the whole canvas (verified: without this, output has no alpha
            // channel at all even with -type truecolormatte set).
            cmd.addAll(List.of("-type", "truecolormatte", "-background", "none"));
        } else {
            // JPEG has no alpha - composite onto white first, mirrors production's IM command
            // (a no-op for opaque sources; only matters for alpha-channel input like PNG/WebP).
            cmd.addAll(List.of("-alpha", "remove", "-type", "truecolor", "-background", "white"));
        }
        cmd.addAll(List.of(
            "-resize", contentW + "x" + contentH + "^",
            "-virtual-pixel", "Mirror",
            "-set", "option:distort:viewport", viewport,
            "-distort", "SRT", "0",
            "-gravity", "center", "-extent", fullW + "x" + fullH,
            outputFormat.ext + ":" + out));
        return cmd;
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }
}
