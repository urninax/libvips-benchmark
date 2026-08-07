package com.journi.bench.local;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsExtend;
import app.photofox.vipsffm.enums.VipsInteresting;
import app.photofox.vipsffm.enums.VipsSize;
import com.journi.bench.BenchConfig;
import com.journi.bench.metrics.RssSampler;
import com.journi.bench.metrics.VipsTrackedMemory;
import com.journi.bench.variant.GmStandardVariant;
import com.journi.bench.variant.ProcessRunner;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Local-file resize benchmark: libvips IN-PROCESS via vips-ffm vs GM/IM forked as CLI
 * subprocesses. No S3.
 *
 * WHY THIS EXISTS: benchmark_resize.sh compares GM/IM against the `vips` CLI, which pays ~70ms
 * of pure process startup per invocation - measured flat across a 24,000x file-size range
 * (a 507-byte file and a 12MB file both cost 0.07s), i.e. ~100% exec + libvips/glib/plugin
 * loading and ~0% real work. For the short operations here that is a large slice of the
 * measurement, so the script systematically understates libvips.
 *
 * Production will not shell out to the vips CLI - it will call libvips in-process from a
 * long-lived JVM and pay that init once at boot. GM and IM genuinely do fork per job today and
 * will keep doing so. This benchmark measures that real asymmetry. The asymmetry is the POINT,
 * not a flaw in the comparison.
 *
 * Commands mirror benchmark_resize.sh verbatim (Lanczos, exact flag order, the per-case `-size`
 * scaled-decode hint) so the two harnesses stay diffable. The GM/IM argv are built inline rather
 * than reusing GmStandardVariant/ImBleedVariant, which are S3-coupled, branch on ImageFormat,
 * still emit `-filter Bessel`, and order flags differently (GM is order-sensitive).
 */
public final class LocalResizeBenchmark {

    private static final int BLEED_PX = 36;
    private static final long PROCESS_TIMEOUT_SECONDS = 120;
    private static final long RSS_SAMPLE_INTERVAL_MS = 5; // not Main's 30ms - some ops are <50ms
    private static final String ICC_PATH = "conf/imagemagick/sRGB.icc";

    private LocalResizeBenchmark() {}

    public static void run(BenchConfig cfg, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("""
                Usage: local-resize <source-image> [output-dir]

                Compares libvips called in-process (vips-ffm) against gm/convert forked as CLI
                subprocesses, on a local file. Mirrors benchmark_resize.sh's operations exactly.

                Must be run from the repo root (needs conf/imagemagick/sRGB.icc) with
                VIPS_CONCURRENCY set, e.g.:
                  VIPS_CONCURRENCY=1 OMP_NUM_THREADS=1 java -jar bench.jar \\
                      local-resize samples/baseline.jpg /results

                Env: LOCAL_WARMUP_RUNS (default 3), LOCAL_MIN_RUNS (default 20),
                     BENCH_ENGINES (default gm,im,vips; add 'vipscli' to also time the vips CLI)

                Running on macOS for local iteration (NOT the target env - the decision-grade
                numbers come from the linux/amd64 container): vips-ffm looks for Linux .so.42
                naming and will not find Homebrew's dylibs, so point it at them explicitly:
                  -Dvipsffm.libpath.vips.override=/opt/homebrew/lib/libvips.42.dylib
                  -Dvipsffm.libpath.glib.override=/opt/homebrew/lib/libglib-2.0.0.dylib
                  -Dvipsffm.libpath.gobject.override=/opt/homebrew/lib/libgobject-2.0.0.dylib
                """);
            System.exit(2);
        }

        String src = args[1];
        if (!Files.isRegularFile(Path.of(src))) {
            System.err.println("Source image not found: " + src);
            System.exit(2);
        }

        // GUARD: libvips concurrency can ONLY be set via the environment before the JVM starts -
        // vips-ffm 1.9.8 exposes no vips_concurrency_set binding (only image_get_concurrency),
        // and auto-init runs in a static initializer, so this cannot be fixed from inside main().
        // Unset means libvips uses every core while ProcessRunner pins gm/convert to one thread,
        // which makes the whole comparison meaningless. Fail rather than publish that.
        String concurrency = System.getenv("VIPS_CONCURRENCY");
        if (concurrency == null || concurrency.isBlank()) {
            System.err.println("""
                FATAL: VIPS_CONCURRENCY is not set.

                libvips would use every available core while gm/convert are pinned to one thread
                by ProcessRunner, so the comparison would be meaningless. This cannot be fixed
                from inside the JVM (vips-ffm exposes no concurrency setter and libvips auto-inits
                in a static initializer) - it must be in the environment before java starts:

                  VIPS_CONCURRENCY=1 OMP_NUM_THREADS=1 java -jar bench.jar local-resize ...
                """);
            System.exit(2);
        }

        Path icc = Path.of(ICC_PATH).toAbsolutePath();
        if (!Files.isRegularFile(icc)) {
            System.err.println("FATAL: " + ICC_PATH + " not found relative to cwd (" + System.getProperty("user.dir") + ").");
            System.err.println("Every gm/convert/vips command below passes it as a profile - run from the repo root.");
            System.exit(2);
        }

        Path outDir = Path.of(args.length >= 3 ? args[2] : cfg.resultsDir());
        String srcExt = srcExtension(src);
        Path formatDir = outDir.resolve(srcExt);
        Path gmDir = formatDir.resolve("gm");
        Path imDir = formatDir.resolve("im");
        Path vipsDir = formatDir.resolve("vips");
        for (Path d : List.of(gmDir, imDir, vipsDir)) Files.createDirectories(d);

        // NON-NEGOTIABLE: libvips caches ~100 operations keyed on their arguments. Every measured
        // rep calls thumbnail with byte-identical arguments, so reps 2..N would hit the cache and
        // report near-zero - a spectacular and completely fake vips win. Sanity check after the
        // run: any vips mean under ~2ms means this did not take effect.
        Vips.disableOperationCache();

        int warmup = envInt("LOCAL_WARMUP_RUNS", 3);
        int measured = envInt("LOCAL_MIN_RUNS", 20);

        Set<String> engines = Arrays.stream(cfg.engines())
            .map(e -> e.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());

        // ---- source facts, read ONCE outside every timed region ----
        // Deliberately not a `gm identify` fork: GmStandardVariant.process() runs one inside its
        // timed region, which would hand GM an extra fork+exec per iteration that the bash script
        // never pays, and would break the hyperfine cross-check.
        int srcW, srcH, iccBytes;
        String srcLoader;
        try (Arena arena = Arena.ofConfined()) {
            VImage probe = VImage.newFromFile(arena, src);
            srcW = probe.getWidth();
            srcH = probe.getHeight();
            VBlob embedded = probe.getBlob("icc-profile-data");
            iccBytes = embedded == null ? -1 : embedded.getBytes().length;
            // Loader name rather than file extension: .HEIC/.heif/.avif all route through
            // heifload, and an extension can lie.
            srcLoader = probe.getString("vips-loader");
        }

        int[] thumb = minFit(srcW, srcH, 320, 320);
        int[] full = GmStandardVariant.areaFit(srcW, srcH, 2_250_000L);
        int[] orig = GmStandardVariant.areaFit(srcW, srcH, 16_777_216L);
        int bleedW = orig[0] + 2 * BLEED_PX;
        int bleedH = orig[1] + 2 * BLEED_PX;

        System.out.println("=== libvips-benchmark: local resize (vips-ffm in-process vs GM/IM subprocess) ===");
        System.out.println("Source: " + src + "  (" + srcW + "x" + srcH + ")");
        System.out.println("Output dir: " + formatDir);
        System.out.println("Computed targets — thumb:" + dim(thumb) + "  mobileFull:" + dim(full)
            + "  original:" + dim(orig));
        System.out.println("warmup=" + warmup + " measuredRuns=" + measured
            + " engines=" + engines + " VIPS_CONCURRENCY=" + concurrency
            + " OMP_NUM_THREADS=" + orUnset(System.getenv("OMP_NUM_THREADS")));

        // The profile-presence trap: --export-profile on a source with NO embedded ICC profile
        // costs libvips ~2x more than on one with a profile (it must synthesise a fallback input
        // profile). Verified this session to be large enough to fully reverse which engine wins.
        // Always report it so a result can never be read without knowing which regime it is in.
        System.out.println("Source ICC profile: " + (iccBytes < 0
            ? "ABSENT — libvips must synthesise a fallback input profile for every --export-profile "
              + "case, which is ~2x more expensive than a source that carries one. Results are NOT "
              + "comparable with a profiled source."
            : "PRESENT (" + iccBytes + " bytes)"));
        if (orig[0] == srcW && orig[1] == srcH) {
            System.out.println("NOTE: the 'original' case is NOT a downscale for this source "
                + "(16.7MP budget >= " + srcW + "x" + srcH + "), so it measures decode + re-encode only.");
        }
        warnIfThreadPinsDoNotApply(srcLoader, concurrency);
        System.out.println();

        boolean procAvailable = Files.isDirectory(Path.of("/proc/self"));
        if (!procAvailable) {
            System.out.println("MEMORY METRICS UNAVAILABLE ON THIS PLATFORM (no /proc) — rss columns will read n/a.");
            System.out.println();
        }
        RssSampler rss = new RssSampler(RSS_SAMPLE_INTERVAL_MS);
        Thread samplerThread = new Thread(rss, "rss-sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();

        // libvips' own allocation counter - the only per-case-meaningful memory number for the
        // in-process rows (see VipsTrackedMemory for why RSS cannot be one).
        VipsTrackedMemory vipsMem = new VipsTrackedMemory(RSS_SAMPLE_INTERVAL_MS);
        Thread vipsMemThread = new Thread(vipsMem, "vips-mem-sampler");
        vipsMemThread.setDaemon(true);
        vipsMemThread.start();
        if (!VipsTrackedMemory.available()) {
            System.out.println("NOTE: vips_tracked_get_mem() did not resolve — vipsPeak will read n/a.");
        }

        String iccStr = icc.toString();

        // ---- standard resize cases ----
        List<LocalCase> standard = new ArrayList<>();
        if (engines.contains("gm")) {
            standard.add(gmCase("GM   thumb " + dim(thumb) + " (jpg q85)", gmDir.resolve("thumb.jpg"),
                gmStandard(dim(thumb), src, iccStr, dim(thumb), true, "jpg", gmDir.resolve("thumb.jpg")), rss));
            standard.add(gmCase("GM   mobileFull " + dim(full) + " (jpg q85)", gmDir.resolve("full.jpg"),
                gmStandard(dim(full), src, iccStr, dim(full), true, "jpg", gmDir.resolve("full.jpg")), rss));
            standard.add(gmCase("GM   mobileFullWebp " + dim(full) + " (webp q85)", gmDir.resolve("full.webp"),
                gmWebp(dim(full), src, iccStr, dim(full), gmDir.resolve("full.webp")), rss));
            standard.add(gmCase("GM   mobileFullPng " + dim(full) + " (png)", gmDir.resolve("full.png"),
                gmPng(dim(full), src, iccStr, dim(full), gmDir.resolve("full.png")), rss));
            standard.add(gmCase("GM   original " + dim(orig) + " (jpg q85, keepExif)", gmDir.resolve("orig.jpg"),
                gmOriginal(srcW + "x" + srcH, src, dim(orig), gmDir.resolve("orig.jpg")), rss));
        }
        if (engines.contains("vips")) {
            Path o1 = vipsDir.resolve("thumb.jpg");
            standard.add(new LocalCase("vips-ffm thumb " + dim(thumb) + " (jpg q85)", "vips", o1,
                () -> { try (Arena a = Arena.ofConfined()) {
                    VImage.thumbnail(a, src, thumb[0],
                            VipsOption.Int("height", thumb[1]),
                            VipsOption.Enum("size", VipsSize.SIZE_FORCE),
                            VipsOption.String("export-profile", iccStr))
                        .jpegsave(o1.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true));
                }}));

            Path o2 = vipsDir.resolve("full.jpg");
            standard.add(new LocalCase("vips-ffm mobileFull " + dim(full) + " (jpg q85)", "vips", o2,
                () -> { try (Arena a = Arena.ofConfined()) {
                    VImage.thumbnail(a, src, full[0],
                            VipsOption.Int("height", full[1]),
                            VipsOption.Enum("size", VipsSize.SIZE_FORCE),
                            VipsOption.String("export-profile", iccStr))
                        // hyphen, not the CLI's underscore spelling in `[optimize_coding=false]`
                        .jpegsave(o2.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true),
                            VipsOption.Boolean("optimize-coding", false));
                }}));

            Path o3 = vipsDir.resolve("full.webp");
            standard.add(new LocalCase("vips-ffm mobileFullWebp " + dim(full) + " (webp q85)", "vips", o3,
                () -> { try (Arena a = Arena.ofConfined()) {
                    VImage.thumbnail(a, src, full[0],
                            VipsOption.Int("height", full[1]),
                            VipsOption.Enum("size", VipsSize.SIZE_FORCE),
                            VipsOption.String("export-profile", iccStr))
                        .webpsave(o3.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true));
                }}));

            Path o4 = vipsDir.resolve("full.png");
            standard.add(new LocalCase("vips-ffm mobileFullPng " + dim(full) + " (png)", "vips", o4,
                () -> { try (Arena a = Arena.ofConfined()) {
                    VImage.thumbnail(a, src, full[0],
                            VipsOption.Int("height", full[1]),
                            VipsOption.Enum("size", VipsSize.SIZE_FORCE),
                            VipsOption.String("export-profile", iccStr))
                        // filter=248 is libpng's PNG_ALL_FILTERS (0xF8), i.e. adaptive per-row
                        // filtering - the equivalent of GM's `-define png:filter-type=6`.
                        // libvips' pngsave DEFAULTS TO NONE (0x08), which is why this row used to
                        // emit a 28% larger file than GM in 4x the speed: it was skipping the
                        // filtering GM was doing, so it compared different work AND different
                        // output. Matched, vips produces a SMALLER file than GM (3.20 vs 3.33 MB)
                        // and is still ~1.6x faster - the honest number, vs the inflated 4.2x.
                        .pngsave(o4.toString(), VipsOption.Boolean("strip", true),
                            VipsOption.Int("filter", 248));
                }}));

            Path o5 = vipsDir.resolve("orig.jpg");
            standard.add(new LocalCase("vips-ffm original " + dim(orig) + " (jpg q85, keepExif)", "vips", o5,
                () -> { try (Arena a = Arena.ofConfined()) {
                    // deliberately asymmetric, mirroring the script: no export-profile, no strip
                    VImage.thumbnail(a, src, orig[0],
                            VipsOption.Int("height", orig[1]),
                            VipsOption.Enum("size", VipsSize.SIZE_FORCE),
                            VipsOption.Boolean("no-rotate", true))
                        .jpegsave(o5.toString(), VipsOption.Int("Q", 85));
                }}));
        }
        if (engines.contains("vipscli")) {
            // Same libvips, same machine, same operation - but forked. Puts the CLI startup cost
            // in the same table as the in-process rows, so the delta is self-evident.
            standard.add(vipsCliCase("vips-cli thumb " + dim(thumb) + " (jpg q85)",
                src, vipsDir.resolve("cli-thumb.jpg"), thumb, iccStr, rss));
            standard.add(vipsCliCase("vips-cli mobileFull " + dim(full) + " (jpg q85)",
                src, vipsDir.resolve("cli-full.jpg"), full, iccStr, rss));
        }

        // ---- bleed cases ----
        List<LocalCase> bleed = new ArrayList<>();
        String bleedLabel = bleedW + "x" + bleedH;
        if (engines.contains("im")) {
            Path out = imDir.resolve("bleed.jpg");
            List<String> argv = imBleed(bleedW, bleedH, src, iccStr, orig, out);
            bleed.add(new LocalCase("IM   bleed page " + bleedLabel + " (jpg q85, mirror-extend " + BLEED_PX + "px)",
                "im", out, () -> ProcessRunner.run(argv, rss, PROCESS_TIMEOUT_SECONDS)));
        }
        if (engines.contains("vips")) {
            Path out = vipsDir.resolve("bleed.jpg");
            bleed.add(new LocalCase("vips-ffm bleed page " + bleedLabel + " (jpg q85, mirror-extend " + BLEED_PX + "px)",
                "vips", out,
                () -> { try (Arena a = Arena.ofConfined()) {
                    VImage.thumbnail(a, src, orig[0],
                            VipsOption.Int("height", orig[1]),
                            VipsOption.Enum("crop", VipsInteresting.INTERESTING_CENTRE),
                            VipsOption.String("export-profile", iccStr))
                        .embed(BLEED_PX, BLEED_PX, bleedW, bleedH,
                            VipsOption.Enum("extend", VipsExtend.EXTEND_MIRROR))
                        .jpegsave(out.toString(), VipsOption.Int("Q", 85), VipsOption.Boolean("strip", true));
                }}));
        }

        var sourceInfo = new HyperfineExport.SourceInfo(
            src, srcExt, srcW, srcH, Files.size(Path.of(src)), iccBytes);

        List<HyperfineExport.CaseResult> standardResults = measureAll(standard, warmup, measured, rss, vipsMem, procAvailable);
        HyperfineExport.write(formatDir.resolve("standard_resize_ffm.md"),
            formatDir.resolve("standard_resize_ffm.json"), sourceInfo, "Standard resize", standardResults);
        System.out.println();
        System.out.println("== Standard resize results written to " + formatDir.resolve("standard_resize_ffm.md") + " ==");

        System.out.println();
        System.out.println("Bleed case — content " + dim(orig) + ", bleed " + BLEED_PX
            + "px/edge, full canvas " + bleedLabel);
        List<HyperfineExport.CaseResult> bleedResults = measureAll(bleed, warmup, measured, rss, vipsMem, procAvailable);
        HyperfineExport.write(formatDir.resolve("bleed_resize_ffm.md"),
            formatDir.resolve("bleed_resize_ffm.json"), sourceInfo, "Bleed resize", bleedResults);
        System.out.println();
        System.out.println("== Bleed resize results written to " + formatDir.resolve("bleed_resize_ffm.md") + " ==");

        rss.stop();
        vipsMem.stop();

        List<HyperfineExport.CaseResult> all = new ArrayList<>(standardResults);
        all.addAll(bleedResults);
        printValidityGates(all);

        System.out.println();
        System.out.println("Charts:  python3 tools/plot_benchmarks.py " + formatDir);
    }

    /**
     * HEIC/AVIF decoding escapes every thread pin this benchmark relies on, so a run against one
     * is only comparable to another run with the SAME cpuset.
     *
     * libheif/libde265 runs its own HEVC decoder thread pool, and none of VIPS_CONCURRENCY,
     * OMP_NUM_THREADS or MAGICK_THREAD_LIMIT reaches it. Verified: with all three pinned to 1, the
     * same 3024x4032 HEIC gives vips real=0.28s / user=0.64s and gm real=0.40s / user=0.75s - user
     * time ~2x wall time, i.e. several threads - while the identical operations on a JPEG source
     * stay strictly single-threaded (real=0.11s / user=0.09s).
     *
     * Consequence seen for real: the same HEIC test run at `taskset -c 0` versus
     * `--cpuset-cpus=0-3` differed by a near-constant ~280ms on EVERY command, GM and IM included
     * - one fixed decode getting cheaper with more cores, not any per-operation speedup.
     */
    private static void warnIfThreadPinsDoNotApply(String loader, String concurrency) {
        if (loader == null || !loader.toLowerCase(Locale.ROOT).startsWith("heif")) return;
        System.out.println();
        System.out.println("WARNING: HEIC/AVIF source (vips-loader=" + loader + ") — the thread pins do NOT reach the decoder.");
        System.out.println("  libheif/libde265 runs its own HEVC decoder thread pool. VIPS_CONCURRENCY=" + concurrency
            + ", OMP_NUM_THREADS and MAGICK_THREAD_LIMIT have no effect on it, for GM/IM and vips alike.");
        System.out.println("  * These timings scale with the container's cpuset. Only compare them against runs");
        System.out.println("    with the SAME --cpuset-cpus; container-level pinning is the only lever that works.");
        System.out.println("  * Unlike the JPEG rows, this does NOT model one single-threaded worker per image —");
        System.out.println("    under a real cores*2 worker pool those decoder threads would contend for cores.");
    }

    // ---- measurement -------------------------------------------------------

    private static List<HyperfineExport.CaseResult> measureAll(List<LocalCase> cases, int warmup, int measured,
                                                               RssSampler rss, VipsTrackedMemory vipsMem,
                                                               boolean procAvailable) throws Exception {
        List<HyperfineExport.CaseResult> out = new ArrayList<>();
        int i = 0;
        for (LocalCase c : cases) {
            System.out.printf(Locale.ROOT, "Benchmark %d: %s%n", ++i, c.name());
            out.add(measure(c, warmup, measured, rss, vipsMem, procAvailable));
            HyperfineExport.CaseResult r = out.get(out.size() - 1);
            var s = com.journi.bench.harness.Stats.summarize(r.seconds());
            System.out.printf(Locale.ROOT, "  Time (mean ± σ):  %8.1f ms ± %5.1f ms    Range: %.1f … %.1f ms  (%d runs)%n%n",
                s.mean() * 1000, s.stddev() * 1000, s.min() * 1000, s.max() * 1000, s.n());
        }
        return out;
    }

    private static HyperfineExport.CaseResult measure(LocalCase c, int warmup, int measured,
                                                      RssSampler rss, VipsTrackedMemory vipsMem,
                                                      boolean procAvailable) throws Exception {
        List<Double> warmupTimes = new ArrayList<>();
        for (int i = 0; i < warmup; i++) {
            long t0 = System.nanoTime();
            c.action().run();
            warmupTimes.add((System.nanoTime() - t0) / 1e9);
        }

        // Baseline AFTER warmup: resetPeaks() only zeroes tracking counters, it cannot make the OS
        // give back heap this JVM already grew into, so without subtracting a baseline a case that
        // happens to run later silently reports memory it never needed.
        System.gc();
        Thread.sleep(50);
        long baselineSelfKb = rss.currentSelfKb();
        rss.resetPeaks();
        vipsMem.resetPeak();

        List<Double> times = new ArrayList<>();
        for (int i = 0; i < measured; i++) {
            // Everything that matters is inside this region and nothing else is. libvips is lazily
            // evaluated: thumbnail/embed only build an operation graph (~0ms) and ALL decode/resize/
            // encode work happens when the save call pulls the pipeline. Timing the stages
            // separately is forbidden - it either reports a meaningless "thumbnail = 0.02ms", or,
            // if evaluation is forced between stages, defeats pipeline fusion and measures a
            // slower pipeline that only exists because it was instrumented.
            long t0 = System.nanoTime();
            c.action().run();
            times.add((System.nanoTime() - t0) / 1e9);
        }

        long selfKb = -1, childrenKb = -1;
        if (procAvailable) {
            selfKb = Math.max(0, rss.peakSelfKb() - baselineSelfKb);
            childrenKb = rss.peakChildrenKb();
        }
        long outBytes = -1;
        try {
            if (Files.isRegularFile(c.output())) outBytes = Files.size(c.output());
        } catch (IOException ignored) {
        }
        return new HyperfineExport.CaseResult(c.name(), c.engine(), times, warmupTimes,
            selfKb, childrenKb, vipsMem.peakKb(), outBytes);
    }

    /**
     * Flags cases where the two engines did not produce equivalent output, so the row cannot be
     * quoted as a clean speedup. Deliberately does NOT assert a cause - investigating this
     * benchmark's own PNG and WebP rows turned up two entirely different ones:
     *
     * - PNG was an encoder-settings mismatch. libvips' pngsave defaults to filter=none while GM
     *   does adaptive filtering, so vips emitted a 28% larger file in a quarter of the time by
     *   skipping work GM was doing. Fixed by passing filter=248 (PNG_ALL_FILTERS); matched, vips
     *   is smaller AND still faster, but by ~1.6x rather than the fictional 4.2x.
     * - WebP was NOT the encoder. Feeding both encoders the SAME pixels gives 308156 vs 307944
     *   bytes - 0.07% apart - and GM turned out to be plain libwebp defaults, byte-identical to
     *   `cwebp -q 85`. The ~11% gap comes from the RESAMPLERS: vips' resize is marginally sharper
     *   than GM's Lanczos (RMSE 0.73% between the two outputs) and sharper images compress worse.
     *   That is a real property of the libraries, not a misconfiguration, and not "fixable".
     */
    private static void printValidityGates(List<HyperfineExport.CaseResult> all) {
        System.out.println();
        System.out.println("---- validity gates ----");
        boolean any = false;
        for (HyperfineExport.CaseResult r : all) {
            var s = com.journi.bench.harness.Stats.summarize(r.seconds());
            if (s.mean() * 1000 < 2.0) {
                any = true;
                System.out.println("WARNING [" + r.name() + "]: mean under 2ms — libvips' operation "
                    + "cache is probably still enabled, which would make repeated identical reps fraudulently fast.");
            }
        }
        for (String suffix : List.of("thumb", "mobileFull ", "mobileFullWebp", "mobileFullPng", "original", "bleed")) {
            Long gm = null, vips = null;
            String gmName = null, vipsName = null;
            for (HyperfineExport.CaseResult r : all) {
                if (!r.name().contains(suffix) || r.outputBytes() < 0) continue;
                if (r.engine().equals("gm") || r.engine().equals("im")) { gm = r.outputBytes(); gmName = r.name(); }
                if (r.engine().equals("vips")) { vips = r.outputBytes(); vipsName = r.name(); }
            }
            if (gm != null && vips != null && gm > 0) {
                double delta = Math.abs(vips - gm) / (double) gm;
                if (delta > 0.10) {
                    any = true;
                    System.out.printf(Locale.ROOT,
                        "WARNING [%s]: outputs are not equivalent — sizes differ by %.0f%% "
                        + "(%s=%dKB vs %s=%dKB). Could be encoder settings or just a different "
                        + "resampler; check before quoting this row as a clean speedup.%n",
                        suffix.trim(), delta * 100, gmName, gm / 1024, vipsName, vips / 1024);
                }
            }
        }
        if (!any) System.out.println("all gates passed");
    }

    // ---- GM / IM / vips-CLI argv (verbatim from benchmark_resize.sh) -------

    private static LocalCase gmCase(String name, Path out, List<String> argv, RssSampler rss) {
        return new LocalCase(name, "gm", out, () -> ProcessRunner.run(argv, rss, PROCESS_TIMEOUT_SECONDS));
    }

    private static List<String> gmStandard(String sizeHint, String src, String icc, String target,
                                           boolean flatten, String fmt, Path out) {
        List<String> cmd = new ArrayList<>(List.of("gm", "convert", "-size", sizeHint, src, "-auto-orient"));
        if (flatten) cmd.addAll(List.of("-type", "truecolor", "-background", "#ffffff", "-flatten"));
        cmd.addAll(List.of("-filter", "Lanczos", "-resize", target + "!",
            "-profile", icc, "-strip", "-colorspace", "RGB", "-quality", "85", fmt + ":" + out));
        return cmd;
    }

    private static List<String> gmWebp(String sizeHint, String src, String icc, String target, Path out) {
        return List.of("gm", "convert", "-size", sizeHint, src, "-auto-orient",
            "-background", "none", "-filter", "Lanczos", "-resize", target + "!",
            "-profile", icc, "-strip", "-colorspace", "RGB", "-quality", "85", "webp:" + out);
    }

    private static List<String> gmPng(String sizeHint, String src, String icc, String target, Path out) {
        return List.of("gm", "convert", "-size", sizeHint, src, "-auto-orient",
            "-define", "png:filter-type=6", "-background", "none",
            "-filter", "Lanczos", "-resize", target + "!",
            "-profile", icc, "-strip", "-colorspace", "RGB", "png:" + out);
    }

    private static List<String> gmOriginal(String sizeHint, String src, String target, Path out) {
        // keepExif: no -auto-orient, no -profile, no -strip. Deliberately asymmetric, per the script.
        return List.of("gm", "convert", "-size", sizeHint, src,
            "-type", "truecolor", "-background", "#ffffff", "-flatten",
            "-filter", "Lanczos", "-resize", target + "!",
            "-colorspace", "RGB", "-quality", "85", "jpg:" + out);
    }

    private static List<String> imBleed(int bleedW, int bleedH, String src, String icc, int[] orig, Path out) {
        // Plain concatenation, NOT String.format - "%" here is IM's literal fx-expression syntax
        // and must stay a single '%' on one argv element (same as ImBleedVariant.bleedCommand()).
        String viewport = "%[fx:w+" + (2 * BLEED_PX) + "]x%[fx:h+" + (2 * BLEED_PX) + "]-" + BLEED_PX + "-" + BLEED_PX;
        return List.of("convert", "-size", bleedW + "x" + bleedH, src,
            "-auto-orient", "-profile", icc, "-strip", "-colorspace", "sRGB",
            "-quality", "85", "-alpha", "remove", "-type", "truecolor",
            "-resize", orig[0] + "x" + orig[1] + "^",
            "-gravity", "center", "-virtual-pixel", "Mirror",
            "-set", "option:distort:viewport", viewport,
            "-distort", "SRT", "0",
            "jpg:" + out);
    }

    private static LocalCase vipsCliCase(String name, String src, Path out, int[] target, String icc, RssSampler rss) {
        List<String> argv = List.of("vips", "thumbnail", src, out + "[Q=85,strip]",
            String.valueOf(target[0]), "--height", String.valueOf(target[1]),
            "--size", "force", "--export-profile", icc);
        return new LocalCase(name, "vipscli", out, () -> ProcessRunner.run(argv, rss, PROCESS_TIMEOUT_SECONDS));
    }

    // ---- helpers -----------------------------------------------------------

    /** Mirrors the script's min_fit: scale so the image covers at least twxth, never upscaling. */
    static int[] minFit(int w, int h, int tw, int th) {
        double scale = Math.max(tw / (double) w, th / (double) h);
        if (scale > 1) scale = 1;
        return new int[]{(int) Math.round(w * scale), (int) Math.round(h * scale)};
    }

    private static String dim(int[] wh) { return wh[0] + "x" + wh[1]; }

    private static String srcExtension(String src) {
        String name = Path.of(src).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "noext" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static int envInt(String key, int fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v.trim());
    }

    private static String orUnset(String v) { return (v == null || v.isBlank()) ? "<unset>" : v; }
}
