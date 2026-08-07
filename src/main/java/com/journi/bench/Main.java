package com.journi.bench;

import com.journi.bench.harness.DrainHarness;
import com.journi.bench.harness.DrainStats;
import com.journi.bench.harness.DrainStatsCodec;
import com.journi.bench.harness.ResultTable;
import com.journi.bench.local.LocalResizeBenchmark;
import com.journi.bench.metrics.RssSampler;
import com.journi.bench.metrics.VipsTrackedMemory;
import com.journi.bench.s3.S3Support;
import com.journi.bench.variant.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class Main {

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "run";
        BenchConfig cfg = BenchConfig.fromEnv();

        switch (cmd) {
            case "seed" -> seed(cfg);
            case "smoke-test" -> smokeTest(cfg);
            case "run" -> run(cfg);
            // Runs exactly ONE variant (given via BENCH_VARIANT_SPEC) and exits - see run()'s
            // subprocess orchestration below. Not meant to be invoked directly by a user.
            case "run-variant" -> runVariant(cfg);
            // Local-file resize benchmark: libvips in-process (vips-ffm) vs gm/convert forked as
            // subprocesses. No S3 - BenchConfig.fromEnv() above only reads env, and no S3 client
            // is constructed unless seed/run is dispatched.
            case "local-resize" -> LocalResizeBenchmark.run(cfg, args);
            default -> throw new IllegalArgumentException("Unknown command: " + cmd + " (expected: run, seed, smoke-test, run-variant, local-resize)");
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

    /**
     * Orchestrator only - spawns one `run-variant` child JVM per variant and collects each
     * result over stdout (see DrainStatsCodec). Does NOT touch S3/libvips/GM itself.
     *
     * WHY SUBPROCESS PER VARIANT: previously every variant ran inside one continuous JVM
     * process (deliberately, to share JIT/connection-pool warmup across REPS of the SAME
     * variant - see DrainHarness's class doc, which is still true and still the reason reps
     * stay in-process). But sharing that ONE process ACROSS variants meant a vips variant's
     * peak RSS was contaminated by whatever GM/IM (or an earlier vips variant) had already left
     * resident - glibc doesn't return freed memory to the OS, so the baseline-then-subtract
     * approach could not produce a trustworthy total for vips (see the old warning that used to
     * live in DrainHarness/DrainStats/ResultTable). Giving each variant its own fresh process
     * makes that baseline valid again: peakTotalRssKb is now a real, comparable total-memory
     * figure for every variant, GM/IM included (whose number was already correct, since their
     * actual image work happens in forked children the kernel tracks independently either way).
     */
    private static void run(BenchConfig cfg) throws Exception {
        Set<String> engines = Arrays.stream(cfg.engines())
            .map(e -> e.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        boolean runGm = engines.contains("gm");
        boolean runIm = engines.contains("im");
        boolean runVips = engines.contains("vips");

        System.out.println("=== libvips-benchmark ===");
        System.out.println("cores=" + cfg.cores() + " queueSize=" + cfg.queueSize()
            + " warmup=" + cfg.warmupReps() + " measuredReps=" + cfg.measuredReps()
            + " inputFormat=" + cfg.inputFormat() + " outputFormat=" + cfg.outputFormat()
            + " engines=" + engines
            // Recorded here, not just in entrypoint.sh's own log line, so it's on the SAME line
            // results get pasted from - which allocator produced a given totalMB/rssChild figure
            // is provenance that has to travel with the numbers, not sit in a separate log line
            // that's easy to leave out when copying results into a report.
            + " allocator=" + System.getenv().getOrDefault("BENCH_ALLOCATOR", "glibc"));

        List<String> standardSpecs = new ArrayList<>();
        if (runGm) standardSpecs.add("gm-standard");
        if (runVips) {
            for (VipsStreamingVariant.Mode mode : VipsStreamingVariant.Mode.values()) {
                standardSpecs.add("vips-standard-" + mode);
            }
        }
        List<DrainStats> standardResults = runIsolated(standardSpecs);
        ResultTable.print("Standard resize", standardResults);

        List<String> bleedSpecs = new ArrayList<>();
        if (runIm) bleedSpecs.add("im-bleed");
        if (runVips) {
            for (VipsStreamingVariant.Mode mode : VipsStreamingVariant.Mode.values()) {
                bleedSpecs.add("vips-bleed-" + mode);
            }
        }
        List<DrainStats> bleedResults = runIsolated(bleedSpecs);
        ResultTable.print("Bleed resize", bleedResults);
    }

    /** Runs each spec's variant in its own child JVM, STRICTLY sequentially - concurrent variants would reintroduce the CPU/memory contention isolation is meant to remove. */
    private static List<DrainStats> runIsolated(List<String> specs) throws Exception {
        List<DrainStats> results = new ArrayList<>();
        for (String spec : specs) {
            DrainStats stats = runVariantSubprocess(spec);
            if (stats != null) results.add(stats);
        }
        return results;
    }

    private static DrainStats runVariantSubprocess(String spec) throws IOException, InterruptedException, URISyntaxException {
        List<String> cmd = new ArrayList<>(javaSubprocessCommand());
        cmd.add("run-variant");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("BENCH_VARIANT_SPEC", spec);
        pb.redirectErrorStream(true);

        Process p = pb.start();
        DrainStats[] holder = new DrainStats[1];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                DrainStats parsed = DrainStatsCodec.decode(line);
                if (parsed != null) {
                    holder[0] = parsed;
                } else {
                    System.out.println(line); // pass the child's own live progress/log output through
                }
            }
        }
        boolean finished = p.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Variant subprocess timed out after 10m: " + spec);
        }
        int exit = p.exitValue();
        if (exit != 0) {
            System.err.println("WARNING: variant subprocess '" + spec + "' exited " + exit + " - excluded from results.");
            return null;
        }
        if (holder[0] == null) {
            System.err.println("WARNING: variant subprocess '" + spec + "' produced no result line - excluded from results.");
            return null;
        }
        return holder[0];
    }

    /**
     * Same java binary + native-access/heap flags as this process' own ENTRYPOINT, resolved from
     * the live JVM/classpath rather than hardcoding "/app/bench.jar" - works the same whether
     * this is the Docker image's baked-in path or a local `java -jar target/bench.jar run` invocation.
     */
    private static List<String> javaSubprocessCommand() throws URISyntaxException {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        String jarPath = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        return List.of(javaBin, "--enable-native-access=ALL-UNNAMED", "-Xms2g", "-Xmx4g", "-jar", jarPath);
    }

    /** Runs exactly one variant (BENCH_VARIANT_SPEC) end-to-end in THIS process, then prints its DrainStats as one machine-readable line for the parent orchestrator to parse. */
    private static void runVariant(BenchConfig cfg) throws Exception {
        String spec = System.getenv("BENCH_VARIANT_SPEC");
        if (spec == null || spec.isBlank()) {
            throw new IllegalStateException(
                "run-variant requires BENCH_VARIANT_SPEC, e.g. gm-standard, im-bleed, vips-standard-B0_BUFF_BUFF");
        }

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

        // This process now runs exactly ONE variant, so peakSelfKb/peakTotalKb (see DrainHarness)
        // are a trustworthy total for THIS variant alone - no earlier variant ever shared this
        // process to contaminate the baseline. vipsTrackedPeakKb remains useful as a secondary,
        // libvips-allocations-only figure (it undercounts libjpeg/libpng/libwebp's own scratch
        // memory - see VipsTrackedMemory's doc), but it is no longer the ONLY trustworthy number.
        VipsTrackedMemory vipsMem = new VipsTrackedMemory(30);
        Thread vipsMemThread = new Thread(vipsMem, "vips-mem-sampler");
        vipsMemThread.setDaemon(true);
        vipsMemThread.start();

        DrainHarness harness = new DrainHarness(rssSampler, vipsMem);

        String inExt = cfg.inputFormat().ext;
        List<String> keys = Arrays.stream(cfg.keys()).map(k -> k + "." + inExt).collect(Collectors.toList());
        List<String> standardKeys = expandToQueueSize(keys, cfg.queueSize());

        Variant variant = buildVariant(spec, s3, cfg, workDir, iccResourcePath, rssSampler);
        DrainStats result = harness.run(variant, cfg, standardKeys);

        rssSampler.stop();
        vipsMem.stop();

        // No human-readable table here - the orchestrator's two final section summaries (see
        // run()) already cover every variant; printing this variant's own table too just
        // duplicated every row (and its footer, before that got fixed) once per subprocess.
        System.out.println(DrainStatsCodec.encode(result)); // machine-readable, for the parent
    }

    /** Parses a BENCH_VARIANT_SPEC ("gm-standard" / "im-bleed" / "vips-<standard|bleed>-<Mode>") into the Variant it names. */
    private static Variant buildVariant(String spec, S3Client s3, BenchConfig cfg, Path workDir,
                                         String iccResourcePath, RssSampler rssSampler) {
        String[] parts = spec.split("-", 3);
        String engine = parts[0];
        return switch (engine) {
            case "gm" -> new GmStandardVariant(s3, cfg.s3Bucket(), Path.of(iccResourcePath), workDir, rssSampler,
                cfg.inputFormat(), cfg.outputFormat());
            case "im" -> new ImBleedVariant(s3, cfg.s3Bucket(), Path.of(iccResourcePath), workDir, rssSampler,
                cfg.inputFormat(), cfg.outputFormat());
            case "vips" -> {
                String operation = parts[1];
                VipsStreamingVariant.Mode mode = VipsStreamingVariant.Mode.valueOf(parts[2]);
                VipsStreamingVariant.Operation op = "bleed".equals(operation)
                    ? VipsStreamingVariant.Operation.BLEED_RESIZE
                    : VipsStreamingVariant.Operation.STANDARD_RESIZE;
                yield new VipsStreamingVariant(s3, cfg.s3Bucket(), mode, op,
                    cfg.partSizeBytes(), iccResourcePath, cfg.inputFormat(), cfg.outputFormat());
            }
            default -> throw new IllegalArgumentException("Unknown engine in BENCH_VARIANT_SPEC: " + spec);
        };
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
