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
    private final ImageFormat inputFormat;
    private final ImageFormat outputFormat;

    public GmStandardVariant(S3Client s3, String bucket, Path iccPath, Path workDir, RssSampler rssSampler,
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
        return "GM standard resize (fet-proc) [" + inputFormat.ext + "->" + outputFormat.ext + "]";
    }

    /**
     * Deliberately NOT cfg.workers(): GM forks a full separate OS process per
     * job, each loading its own copy of libGraphicsMagick and decoding the
     * image into private memory - unlike vips' in-process, arena-based
     * concurrency, this doesn't scale gracefully with worker count. Confirmed
     * via OOM: 32 concurrent `convert`/`gm` children plus the JVM's own
     * footprint exceeded this container's 8g mem_limit, when the point of
     * raising BENCH_WORKERS was to find vips' own ceiling, not to blow up the
     * fixed baseline it's being compared against. Capped at the already-
     * proven-safe cores*2 model regardless of how high BENCH_WORKERS goes.
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
            inFile = ProcessRunner.tempFile(workDir, "gm-in-", "." + inputFormat.ext);
            Files.write(inFile, full);
            long fetchEnd = System.nanoTime();

            // NOTE ON THE PROCESS METRIC: identifyDims() below is a second `gm identify` fork,
            // timed inside procStart/procEnd alongside the actual resize. vips never pays this -
            // thumbnailBuffer/thumbnailSource read dimensions off the header for free as part of
            // the same decode call. This mirrors production's GraphicsMagickClient.
            // readBasicImageInfo() (see class doc), so it's not being removed - but that also
            // means the GM "process" column in ResultTable is (metadata read + resize), not pure
            // resize, and isn't an apples-to-apples comparison against vips' process column.
            long procStart = System.nanoTime();
            int[] srcDims = identifyDims(inFile);
            int[] target = areaFit(srcDims[0], srcDims[1], TARGET_PIXELS);
            outFile = ProcessRunner.tempFile(workDir, "gm-out-", "." + outputFormat.ext);
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

    /** Also used by ImBleedVariant - IM's real bleedOperation needs the source's actual dimensions too (see its doc). Uses GM's binary purely for a dimension read; interchangeable with IM's own `identify` for that. */
    public static int[] identifyDims(Path file) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("gm", "identify", "-format", "%w %h", file.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes());
        }
        boolean finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("gm identify timed out after 10s: " + file);
        }
        int exit = p.exitValue();
        if (exit != 0) {
            throw new IOException("gm identify exited " + exit + ": " + out);
        }
        String[] parts = out.trim().split("\\s+");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    /** Mirrors ResizeMode.Area: scale to fit a total pixel budget, preserving aspect ratio. */
    public static int[] areaFit(int w, int h, long targetPixels) {
        double scale = Math.sqrt((double) targetPixels / (w * (double) h));
        if (scale > 1) scale = 1;
        return new int[]{(int) Math.round(w * scale), (int) Math.round(h * scale)};
    }

    private List<String> gmCommand(Path in, Path out, int w, int h) {
        // -size before the source path is a libjpeg read-time hint (DCT-domain scaled
        // decompression) - without it GM fully decodes at source resolution before discarding
        // most of it on resize, while vips gets the equivalent shrink-on-load for free. Same
        // fix as b9814e7 in benchmark_resize.sh, ported here (was never applied to this
        // harness, so the standard-resize row was comparing "does GM get a decode shortcut"
        // rather than actual resize cost, and the gap grew with source resolution).
        List<String> cmd = new ArrayList<>(List.of("gm", "convert",
            "-size", w + "x" + h, in.toString(),
            "-auto-orient",
            "-profile", iccPath.toString(),
            "-strip", "-colorspace", "RGB"));
        if (outputFormat.supportsQuality) {
            cmd.addAll(List.of("-quality", "85"));
        }
        if (outputFormat.supportsAlpha) {
            // Preserve any alpha channel the source has - PNG/WebP output can carry it.
            cmd.addAll(List.of("-type", "truecolormatte"));
        } else {
            // JPEG has no alpha - composite onto white first, mirrors production's GM command
            // (a no-op for opaque sources; only matters for alpha-channel input like PNG/WebP).
            cmd.addAll(List.of("-type", "truecolor", "-background", "#ffffff", "-flatten"));
        }
        cmd.addAll(List.of(
            // Lanczos, not Bessel: matches vips' thumbnail default kernel (lanczos3) and the
            // other two harnesses (LocalResizeBenchmark, benchmark_resize.sh). Bessel is a
            // different, cheaper-to-evaluate kernel - leaving it in here made this harness's
            // numbers incomparable to the other two AND meant GM and vips weren't even doing
            // the same resampling algorithm.
            "-filter", "Lanczos",
            "-resize", w + "x" + h + "!",
            outputFormat.ext + ":" + out));
        return cmd;
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }
}
