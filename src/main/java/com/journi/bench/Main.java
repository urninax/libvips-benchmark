package com.journi.bench;

import com.journi.bench.harness.DrainHarness;
import com.journi.bench.harness.DrainStats;
import com.journi.bench.harness.ResultTable;
import com.journi.bench.metrics.RssSampler;
import com.journi.bench.s3.S3Support;
import com.journi.bench.variant.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class Main {

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "run";
        BenchConfig cfg = BenchConfig.fromEnv();

        switch (cmd) {
            case "seed" -> seed(cfg);
            case "smoke-test" -> smokeTest(cfg);
            case "run" -> run(cfg);
            default -> throw new IllegalArgumentException("Unknown command: " + cmd + " (expected: run, seed, smoke-test)");
        }
    }

    /** Uploads baseline/progressive fixtures (in cfg.inputFormat()) from samples/ under N distinct keys, so every job fetches a distinct object. */
    private static void seed(BenchConfig cfg) throws IOException {
        S3Client s3 = S3Support.buildClient(cfg);
        ensureBucket(s3, cfg.s3Bucket());

        String ext = cfg.inputFormat().ext;
        byte[] baseline = Files.readAllBytes(Path.of("samples/baseline." + ext));
        byte[] progressive = Files.readAllBytes(Path.of("samples/progressive." + ext));

        upload(s3, cfg.s3Bucket(), "fixtures/baseline." + ext, baseline);
        upload(s3, cfg.s3Bucket(), "fixtures/progressive." + ext, progressive);

        System.out.println("Seeded fixtures/baseline." + ext + " (" + baseline.length + " bytes) and "
            + "fixtures/progressive." + ext + " (" + progressive.length + " bytes) into bucket " + cfg.s3Bucket());
    }

    private static void ensureBucket(S3Client s3, String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    private static void upload(S3Client s3, String bucket, String key, byte[] bytes) {
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentLength((long) bytes.length).build(),
            software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
    }

    /** Build-time sanity check: can vips-ffm actually bind to the installed native libvips? Fails the BUILD, not a 3am run. */
    private static void smokeTest(BenchConfig cfg) {
        try {
            app.photofox.vipsffm.Vips.init();
            System.out.println("vips-ffm: native libvips bound successfully.");
        } catch (Throwable t) {
            System.err.println("vips-ffm smoke test FAILED - native libvips could not be loaded/bound.");
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(BenchConfig cfg) throws Exception {
        S3Client s3 = S3Support.buildClient(cfg);
        ensureBucket(s3, cfg.s3Bucket());

        Path workDir = Path.of("/tmp/bench-work");
        Files.createDirectories(workDir);
        String iccResourcePath = "/tmp/bench-work/sRGB.icc";
        try (var in = Main.class.getResourceAsStream("/sRGB.icc")) {
            Files.write(Path.of(iccResourcePath), in.readAllBytes());
        }

        RssSampler rssSampler = new RssSampler(30);
        Thread samplerThread = new Thread(rssSampler, "rss-sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();

        DrainHarness harness = new DrainHarness(rssSampler);

        // BENCH_ENGINES gates which variants actually run - e.g. BENCH_ENGINES=vips skips
        // the GM/IM baseline entirely, useful when iterating on vips-only behavior (streaming
        // modes, format support) without paying for GM/IM's slower fork-per-job cost every run.
        Set<String> engines = Arrays.stream(cfg.engines())
            .map(e -> e.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        boolean runGm = engines.contains("gm");
        boolean runIm = engines.contains("im");
        boolean runVips = engines.contains("vips");

        String inExt = cfg.inputFormat().ext;
        List<String> keys = Arrays.stream(cfg.keys()).map(k -> k + "." + inExt).collect(Collectors.toList());
        System.out.println("=== libvips-benchmark ===");
        System.out.println("cores=" + cfg.cores() + " queueSize=" + cfg.queueSize()
            + " warmup=" + cfg.warmupReps() + " measuredReps=" + cfg.measuredReps()
            + " inputFormat=" + cfg.inputFormat() + " outputFormat=" + cfg.outputFormat()
            + " engines=" + engines
            + " keys=" + keys);

        List<String> standardKeys = expandToQueueSize(keys, cfg.queueSize());

        // ---- Standard resize ----
        List<DrainStats> standardResults = new ArrayList<>();
        if (runGm) {
            standardResults.add(harness.run(
                new GmStandardVariant(s3, cfg.s3Bucket(), Path.of(iccResourcePath), workDir, rssSampler,
                    cfg.inputFormat(), cfg.outputFormat()),
                cfg, standardKeys));
        }
        if (runVips) {
            for (VipsStreamingVariant.Mode mode : VipsStreamingVariant.Mode.values()) {
                standardResults.add(harness.run(
                    new VipsStreamingVariant(s3, cfg.s3Bucket(), mode, VipsStreamingVariant.Operation.STANDARD_RESIZE,
                        cfg.partSizeBytes(), iccResourcePath, cfg.inputFormat(), cfg.outputFormat()),
                    cfg, standardKeys));
            }
        }
        ResultTable.print("Standard resize", standardResults);

        // ---- Bleed resize ----
        List<DrainStats> bleedResults = new ArrayList<>();
        if (runIm) {
            bleedResults.add(harness.run(
                new ImBleedVariant(s3, cfg.s3Bucket(), Path.of(iccResourcePath), workDir, rssSampler,
                    cfg.inputFormat(), cfg.outputFormat()),
                cfg, standardKeys));
        }
        if (runVips) {
            for (VipsStreamingVariant.Mode mode : VipsStreamingVariant.Mode.values()) {
                bleedResults.add(harness.run(
                    new VipsStreamingVariant(s3, cfg.s3Bucket(), mode, VipsStreamingVariant.Operation.BLEED_RESIZE,
                        cfg.partSizeBytes(), iccResourcePath, cfg.inputFormat(), cfg.outputFormat()),
                    cfg, standardKeys));
            }
        }
        ResultTable.print("Bleed resize", bleedResults);

        rssSampler.stop();
    }

    /** Repeats the seeded fixture keys to fill a queue of the target size, cycling baseline/progressive. */
    private static List<String> expandToQueueSize(List<String> baseKeys, int queueSize) {
        List<String> expanded = new ArrayList<>(queueSize);
        for (int i = 0; i < queueSize; i++) {
            expanded.add(baseKeys.get(i % baseKeys.size()));
        }
        return expanded;
    }
}
