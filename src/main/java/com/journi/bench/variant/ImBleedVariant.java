package com.journi.bench.variant;

import com.journi.bench.BenchConfig;
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
import java.util.List;

/**
 * Variant A (bleed): ImageMagick fetch-then-process, mirroring
 * ImageMagickClient.buildCmd + bleedOperation exactly. Content 2480x3508
 * (A4@300dpi approximation), bleed 36px/edge (3mm @ 300dpi, ProductConstants.
 * DEFAULT_BLEED), full canvas 2552x3580.
 */
public final class ImBleedVariant implements Variant {

    private static final int CONTENT_W = 2480, CONTENT_H = 3508;
    private static final int BLEED_PX = 36;
    private static final int FULL_W = CONTENT_W + 2 * BLEED_PX;
    private static final int FULL_H = CONTENT_H + 2 * BLEED_PX;
    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    private final S3Client s3;
    private final String bucket;
    private final Path iccPath;
    private final Path workDir;
    private final RssSampler rssSampler;

    public ImBleedVariant(S3Client s3, String bucket, Path iccPath, Path workDir, RssSampler rssSampler) {
        this.s3 = s3;
        this.bucket = bucket;
        this.iccPath = iccPath;
        this.workDir = workDir;
        this.rssSampler = rssSampler;
    }

    @Override
    public String name() {
        return "IM bleed resize (fetch-then-process)";
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
            inFile = ProcessRunner.tempFile(workDir, "im-in-", ".jpg");
            Files.write(inFile, full);
            long fetchEnd = System.nanoTime();

            long procStart = System.nanoTime();
            outFile = ProcessRunner.tempFile(workDir, "im-out-", ".jpg");
            ProcessRunner.run(bleedCommand(inFile, outFile), rssSampler, PROCESS_TIMEOUT_SECONDS);
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
                full.length, outLen, FULL_W, FULL_H);
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

    private List<String> bleedCommand(Path in, Path out) {
        // Plain concatenation, NOT String.format - "%" here is IM's literal fx-expression
        // syntax and must NOT be doubled to "%%" (that would only be needed inside a
        // format string, which this isn't - single '%' is correct on the raw argv element).
        String viewport = "%[fx:w+" + (2 * BLEED_PX) + "]x%[fx:h+" + (2 * BLEED_PX) + "]-" + BLEED_PX + "-" + BLEED_PX;
        return List.of("convert",
            "-size", FULL_W + "x" + FULL_H, in.toString(),
            "-auto-orient",
            "-profile", iccPath.toString(),
            "-strip", "-colorspace", "sRGB",
            "-quality", "85", "-alpha", "remove", "-type", "truecolor",
            "-resize", CONTENT_W + "x" + CONTENT_H + "^",
            "-virtual-pixel", "Mirror",
            "-set", "option:distort:viewport", viewport,
            "-distort", "SRT", "0",
            "-gravity", "center", "-extent", FULL_W + "x" + FULL_H,
            "jpg:" + out);
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }
}
