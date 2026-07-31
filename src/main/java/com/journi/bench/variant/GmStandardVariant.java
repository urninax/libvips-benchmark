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
 * Variant A: GM fetch-then-process (mirrors production exactly).
 * GraphicsMagickClient.buildCmd / GraphicsMagickClient.readBasicImageInfo,
 * PictureFormat.mobileFullFormat (1500x1500 area-fit, i.e. ~2 megapixel budget).
 *
 * Fetch is fully buffered (mirrors AbstractS3Storage.get() at :138-142, which
 * reads the full stream into memory before returning) then spilled to a temp
 * file since GM is path-based. That double-materialization (heap buffer, then
 * disk write) IS a real cost of the path-based design streaming eliminates -
 * timed and counted under fetchNanos, not hidden.
 */
public final class GmStandardVariant implements Variant {

    private static final long TARGET_PIXELS = 2_250_000L; // PictureFormat.mobileFullFormat area budget
    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    private final S3Client s3;
    private final String bucket;
    private final Path iccPath;
    private final Path workDir;
    private final RssSampler rssSampler;

    public GmStandardVariant(S3Client s3, String bucket, Path iccPath, Path workDir, RssSampler rssSampler) {
        this.s3 = s3;
        this.bucket = bucket;
        this.iccPath = iccPath;
        this.workDir = workDir;
        this.rssSampler = rssSampler;
    }

    @Override
    public String name() {
        return "GM standard resize (fetch-then-process)";
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
            inFile = ProcessRunner.tempFile(workDir, "gm-in-", ".jpg");
            Files.write(inFile, full);
            long fetchEnd = System.nanoTime();

            long procStart = System.nanoTime();
            int[] srcDims = identifyDims(inFile);
            int[] target = areaFit(srcDims[0], srcDims[1], TARGET_PIXELS);
            outFile = ProcessRunner.tempFile(workDir, "gm-out-", ".jpg");
            ProcessRunner.run(gmCommand(inFile, outFile, target[0], target[1]), rssSampler, PROCESS_TIMEOUT_SECONDS);
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
                full.length, outLen, target[0], target[1]);
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

    private int[] identifyDims(Path file) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("gm", "identify", "-format", "%w %h", file.toString())
            .redirectErrorStream(false)
            .start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        String[] parts = out.trim().split("\\s+");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    /** Mirrors ResizeMode.Area: scale to fit a total pixel budget, preserving aspect ratio. */
    static int[] areaFit(int w, int h, long targetPixels) {
        double scale = Math.sqrt((double) targetPixels / (w * (double) h));
        if (scale > 1) scale = 1;
        return new int[]{(int) Math.round(w * scale), (int) Math.round(h * scale)};
    }

    private List<String> gmCommand(Path in, Path out, int w, int h) {
        return List.of("gm", "convert", in.toString(),
            "-auto-orient",
            "-profile", iccPath.toString(),
            "-strip", "-colorspace", "RGB",
            "-quality", "85", "-type", "truecolor",
            "-background", "#ffffff", "-flatten",
            "-filter", "Bessel",
            "-resize", w + "x" + h + "!",
            "jpg:" + out);
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }
}
