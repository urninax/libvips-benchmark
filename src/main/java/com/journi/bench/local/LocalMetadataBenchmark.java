package com.journi.bench.local;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VSource;
import app.photofox.vipsffm.Vips;
import com.journi.bench.BenchConfig;
import com.journi.bench.harness.Stats;
import com.journi.bench.metrics.RssSampler;
import com.journi.bench.metrics.VipsTrackedMemory;
import com.journi.bench.variant.ProcessRunner;

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
 * Metadata-extraction benchmark: GM forked as a CLI subprocess (what production does, and will
 * keep doing) vs libvips called IN-PROCESS through vips-ffm (what production will do after the
 * migration).
 *
 * WHY THIS EXISTS - benchmark_metadata.sh answers the wrong question and inverts the result.
 * That script times `vipsheader -a` against `gm identify`, i.e. two CLI binaries. Measured on this
 * repo's own fixtures, vipsheader costs ~70ms on a 507-byte file and ~70ms on a 12MB one: the
 * number is flat across a 24,000x size range, so it is ~100% exec + libvips/glib/plugin loading
 * and ~0% metadata work. GM's binary starts far faster. The script therefore reports GM winning
 * comfortably.
 *
 * Production will never invoke `vipsheader`. It will call libvips in a long-lived JVM and pay that
 * init exactly once at boot. GM genuinely does fork per image and will keep doing so. Measured in
 * those two shapes on samples/sample_original.jpg, the ordering reverses outright: ~1.4ms
 * in-process vs ~15.6ms for the two forks production actually performs. Same two libraries,
 * opposite conclusion - which is why this had to move out of bash and into the JVM.
 *
 * Three asymmetries the CLI-vs-CLI script cannot express, all modelled here:
 * <ol>
 *   <li>Production makes TWO `gm identify` calls per image (basic, then full+GPS), so GM is
 *       charged two forks, not one.</li>
 *   <li>Those forks come from a JVM via ProcessBuilder, not from bash - forking a multi-GB heap
 *       is not the same operation as forking a shell.</li>
 *   <li>In the presigned-URL/S3 architecture the bytes arrive in memory. GM is path-based and
 *       needs them spilled to a temp file first; vips-ffm reads a VSource directly. That spill is
 *       a real, GM-only cost that only appears if it is modelled - so there is a case for it.</li>
 * </ol>
 *
 * The `vipscli` engine adds `vipsheader -a` as a REFERENCE row, so the startup cost the old script
 * was really measuring is visible in the same table as the in-process rows instead of being
 * something a reader has to be told about.
 */
public final class LocalMetadataBenchmark {

    private static final long PROCESS_TIMEOUT_SECONDS = 30;
    private static final long RSS_SAMPLE_INTERVAL_MS = 5; // metadata reads are ~1ms; Main's 30ms would miss them entirely

    /**
     * Below this, an in-process row is not believable for a real header parse and almost certainly
     * means the operation cache is live (jpegload is a cached operation keyed on its arguments, and
     * every rep passes byte-identical arguments) or the JIT deleted the work. Calibrated against
     * the ~1.4ms measured for newFromFile + nine field reads on a 9.1MP iPhone JPEG.
     */
    private static final double IN_PROCESS_TOO_FAST_MS = 0.10;

    private LocalMetadataBenchmark() {}

    public static void run(BenchConfig cfg, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("""
                Usage: local-metadata <source-image> [output-dir]

                Compares metadata extraction as PRODUCTION performs it: GM forked per image
                (2x `gm identify`) vs libvips in-process via vips-ffm. Both engines produce the
                same parsed ImageMeta record, and both parse inside the timed region.

                  VIPS_CONCURRENCY=1 java -jar bench.jar local-metadata samples/sample_original.jpg /results

                Pass a REAL photo that still carries EXIF (straight off a phone, not -strip'ed).
                A fixture with no EXIF turns this into a pure "who starts faster" race with none
                of the EXIF/GPS parsing the comparison exists to cover - the run warns if so.

                Env: LOCAL_WARMUP_RUNS (default 3), LOCAL_MIN_RUNS (default 20),
                     BENCH_ENGINES (default gm,vips; add 'vipscli' for the vipsheader reference row)

                macOS local iteration (NOT the target env) needs explicit dylib paths:
                  -Dvipsffm.libpath.vips.override=/opt/homebrew/lib/libvips.42.dylib
                  -Dvipsffm.libpath.glib.override=/opt/homebrew/lib/libglib-2.0.0.dylib
                  -Dvipsffm.libpath.gobject.override=/opt/homebrew/lib/libgobject-2.0.0.dylib
                """);
            System.exit(2);
        }

        String src = args[1];
        Path srcPath = Path.of(src);
        if (!Files.isRegularFile(srcPath)) {
            System.err.println("Source image not found: " + src);
            System.exit(2);
        }

        // Deliberately a NOTE, not the hard exit LocalResizeBenchmark uses. There, an unpinned
        // libvips would use every core against a one-thread-pinned gm and invalidate everything.
        // Here nothing decodes pixels - a header read never starts libvips' threadpool - so the
        // pin does not bite. Still reported, because it is part of the run's provenance.
        String concurrency = System.getenv("VIPS_CONCURRENCY");
        if (concurrency == null || concurrency.isBlank()) {
            concurrency = "<unset>";
        }

        Path outDir = Path.of(args.length >= 3 ? args[2] : cfg.resultsDir());
        String srcExt = srcExtension(src);
        Path formatDir = outDir.resolve(srcExt);
        Path spillDir = formatDir.resolve("gm-spill");
        Files.createDirectories(spillDir);

        // NON-NEGOTIABLE, and more dangerous here than in the resize benchmark: a header read is
        // ~1ms, so a cache hit does not just flatter vips, it rounds the entire measurement to
        // zero. See IN_PROCESS_TOO_FAST_MS - the validity gate below re-checks this from the data.
        Vips.disableOperationCache();

        int warmup = envInt("LOCAL_WARMUP_RUNS", 3);
        int measured = envInt("LOCAL_MIN_RUNS", 20);

        // Default gm,vips - NOT the cfg default of gm,im,vips. ImageMagick has no role here:
        // production's metadata path is GraphicsMagick's `identify`, and IM's bleed command is a
        // resize concern.
        String enginesRaw = System.getenv("BENCH_ENGINES");
        Set<String> engines = Arrays.stream((enginesRaw == null || enginesRaw.isBlank() ? "gm,vips" : enginesRaw).split(","))
            .map(e -> e.trim().toLowerCase(Locale.ROOT))
            .filter(e -> !e.isEmpty())
            .collect(Collectors.toSet());

        // Read once, OUTSIDE every timed region: the bytes a streamed/S3 caller would already hold
        // in memory. Loading them per-rep would charge both in-memory cases a disk read that the
        // real architecture does not perform at that point.
        byte[] srcBytes = Files.readAllBytes(srcPath);

        int srcW, srcH, exifFieldCount, iccBytes;
        String srcLoader;
        try (Arena arena = Arena.ofConfined()) {
            VImage probe = VImage.newFromFile(arena, src);
            srcW = probe.getWidth();
            srcH = probe.getHeight();
            srcLoader = probe.getString("vips-loader");
            exifFieldCount = (int) probe.getFields().stream().filter(f -> f.startsWith("exif-ifd")).count();
            var embedded = probe.getBlob("icc-profile-data");
            iccBytes = embedded == null ? -1 : embedded.getBytes().length;
        }

        System.out.println("=== libvips-benchmark: metadata extraction (vips-ffm in-process vs GM subprocess) ===");
        System.out.println("Source: " + src + "  (" + srcW + "x" + srcH + ", loader=" + srcLoader
            + ", " + srcBytes.length + " bytes)");
        System.out.println("EXIF fields present: " + exifFieldCount);
        System.out.println("Output dir: " + formatDir);
        System.out.println("warmup=" + warmup + " measuredRuns=" + measured
            + " engines=" + engines + " VIPS_CONCURRENCY=" + concurrency);
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

        VipsTrackedMemory vipsMem = new VipsTrackedMemory(RSS_SAMPLE_INTERVAL_MS);
        Thread vipsMemThread = new Thread(vipsMem, "vips-mem-sampler");
        vipsMemThread.setDaemon(true);
        vipsMemThread.start();

        // ---- equivalence check, run ONCE outside the timed region ----
        ImageMeta gmMeta = null, vipsMeta = null;
        if (engines.contains("gm")) gmMeta = gmExtract(src, rss);
        if (engines.contains("vips")) vipsMeta = vipsExtractFromFile(src);

        List<LocalCase> cases = new ArrayList<>();

        if (engines.contains("gm")) {
            cases.add(new LocalCase("GM   identify x2 (local path — today's shape)", "gm", null,
                () -> LocalMeasure.blackhole = gmExtract(src, rss)));

            // The S3 shape: bytes are already in memory, GM cannot read them, so they must hit
            // disk first. tmpfs or not, that is a write + two opens + an unlink per image that
            // the in-process path simply does not perform.
            cases.add(new LocalCase("GM   identify x2 + temp-file spill (S3 shape)", "gm", null,
                () -> {
                    Path tmp = ProcessRunner.tempFile(spillDir, "meta-", "." + srcExt);
                    try {
                        Files.write(tmp, srcBytes);
                        LocalMeasure.blackhole = gmExtract(tmp.toString(), rss);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                }));
        }

        if (engines.contains("vips")) {
            cases.add(new LocalCase("vips-ffm header+EXIF (from file)", "vips", null,
                () -> LocalMeasure.blackhole = vipsExtractFromFile(src)));

            cases.add(new LocalCase("vips-ffm header+EXIF (from memory — S3 shape)", "vips", null,
                () -> LocalMeasure.blackhole = vipsExtractFromBytes(srcBytes)));
        }

        if (engines.contains("vipscli")) {
            // NOT a production shape - present so the ~70ms of pure process startup that
            // benchmark_metadata.sh was really measuring sits in the same table as the rows that
            // do not pay it, instead of being a caveat in a comment somebody skips.
            List<String> argv = List.of("vipsheader", "-a", src);
            cases.add(new LocalCase("vips-cli vipsheader -a (REFERENCE — not the production shape)",
                "vipscli", null,
                () -> LocalMeasure.blackhole = ProcessRunner.runCapturing(argv, rss, PROCESS_TIMEOUT_SECONDS)));
        }

        var sourceInfo = new HyperfineExport.SourceInfo(
            src, srcExt, srcW, srcH, srcBytes.length, iccBytes);

        LocalMeasure.MemorySnapshot memBefore = LocalMeasure.MemorySnapshot.take(rss);

        List<HyperfineExport.CaseResult> results =
            LocalMeasure.measureAll(cases, warmup, measured, rss, vipsMem, procAvailable);
        HyperfineExport.write(formatDir.resolve("metadata_extract_ffm.md"),
            formatDir.resolve("metadata_extract_ffm.json"), sourceInfo, "Metadata extraction", results);
        System.out.println();
        System.out.println("== Metadata results written to " + formatDir.resolve("metadata_extract_ffm.md") + " ==");

        LocalMeasure.printMemoryCoverage(memBefore, rss, results);

        rss.stop();
        vipsMem.stop();

        printGates(results, gmMeta, vipsMeta, exifFieldCount);
    }

    // ---- extraction --------------------------------------------------------

    /**
     * Exactly what production performs per image: TWO forks. The basic call's result is parsed and
     * discarded rather than skipped, because production's readBasicImageInfo genuinely makes it -
     * dropping it would hand GM a 2x saving this benchmark has no right to give it.
     */
    private static ImageMeta gmExtract(String path, RssSampler rss) throws Exception {
        String basic = ProcessRunner.runCapturing(
            List.of("gm", "identify", "-format", ImageMeta.GM_BASIC_FMT, path), rss, PROCESS_TIMEOUT_SECONDS);
        String full = ProcessRunner.runCapturing(
            List.of("gm", "identify", "-format", ImageMeta.GM_FULL_FMT, path), rss, PROCESS_TIMEOUT_SECONDS);
        // Parsed, not just captured - the caller needs a record, not a string, and that parse is
        // part of the cost of using GM.
        ImageMeta basicMeta = ImageMeta.fromGmBasic(basic);
        ImageMeta result = ImageMeta.fromGmFull(full);
        if (basicMeta.width() == null || result.width() == null) {
            throw new IllegalStateException("gm identify returned no width for " + path
                + " (basic='" + basic.trim() + "', full='" + full.trim() + "')");
        }
        return result;
    }

    private static ImageMeta vipsExtractFromFile(String path) {
        // ofConfined, NOT ofShared: nothing here registers a callback, so libvips never invokes us
        // from one of its own threads, and a confined arena is materially cheaper to close (a
        // shared arena's close needs a JVM-wide thread handshake).
        try (Arena a = Arena.ofConfined()) {
            return extract(VImage.newFromFile(a, path));
        }
    }

    private static ImageMeta vipsExtractFromBytes(byte[] bytes) {
        try (Arena a = Arena.ofConfined()) {
            return extract(VImage.newFromSource(a, VSource.newFromBytes(a, bytes)));
        }
    }

    /**
     * Header-only: newFromFile/newFromSource build a loader and read the header, and because
     * libvips is lazily evaluated no pixel is ever decoded here - nothing downstream pulls the
     * pipeline.
     *
     * Reads "orientation" rather than "exif-ifd0-Orientation": libvips normalises the former to a
     * plain int, while the latter comes back as the decorated string
     * "1 (Top-left, Short, 1 components, 2 bytes)".
     */
    private static ImageMeta extract(VImage im) {
        return new ImageMeta(
            im.getString("vips-loader"),
            im.getWidth(),
            im.getHeight(),
            im.getInt("orientation"),
            ImageMeta.stripVipsDecoration(im.getString("exif-ifd0-Make")),
            ImageMeta.stripVipsDecoration(im.getString("exif-ifd0-Model")),
            ImageMeta.stripVipsDecoration(im.getString("exif-ifd3-GPSLongitude")),
            ImageMeta.stripVipsDecoration(im.getString("exif-ifd3-GPSLongitudeRef")),
            ImageMeta.stripVipsDecoration(im.getString("exif-ifd3-GPSLatitude")),
            ImageMeta.stripVipsDecoration(im.getString("exif-ifd3-GPSLatitudeRef")));
    }

    // ---- gates -------------------------------------------------------------

    private static void printGates(List<HyperfineExport.CaseResult> results,
                                   ImageMeta gm, ImageMeta vips, int exifFieldCount) {
        System.out.println();
        System.out.println("---- validity gates ----");
        boolean any = false;

        if (exifFieldCount == 0) {
            any = true;
            System.out.println("WARNING: source carries NO EXIF — every EXIF/GPS field is absent for both");
            System.out.println("  engines, so this run degenerates into a pure process-startup race and does not");
            System.out.println("  exercise the parsing the comparison exists to measure. Use a real camera photo.");
        }

        for (HyperfineExport.CaseResult r : results) {
            if (!r.engine().equals("vips")) continue;
            Stats.Summary s = Stats.summarize(r.seconds());
            if (s.mean() * 1000 < IN_PROCESS_TOO_FAST_MS) {
                any = true;
                System.out.printf(Locale.ROOT,
                    "WARNING [%s]: mean %.3f ms is below the %.2f ms floor for a real header parse —%n"
                    + "  libvips' operation cache is probably still live, or the JIT eliminated the work.%n",
                    r.name(), s.mean() * 1000, IN_PROCESS_TOO_FAST_MS);
            }
        }

        if (gm != null && vips != null) {
            List<String> diffs = gm.diff(vips);
            if (diffs.isEmpty()) {
                System.out.println("record equivalence: OK — GM and vips-ffm produced the same normalised ImageMeta");
            } else {
                any = true;
                System.out.println("WARNING: GM and vips-ffm did NOT produce equivalent records — these rows are not");
                System.out.println("  a like-for-like speedup until the gaps below are closed in the extraction code:");
                for (String d : diffs) System.out.println("    " + d);
            }
            System.out.println();
            System.out.println("  gm   -> " + gm);
            System.out.println("  vips -> " + vips);
        }

        if (!any) System.out.println("all gates passed");

        System.out.println();
        System.out.println("READ BEFORE QUOTING: the GM rows are per-image forks and stay that way in production.");
        System.out.println("The vips-ffm rows pay libvips init ONCE at JVM boot, which is excluded here by design —");
        System.out.println("that is the actual production shape, not a thumb on the scale. A `vipsheader` number");
        System.out.println("(BENCH_ENGINES=...,vipscli) is ~100% startup and must never be read as libvips' cost.");
    }

    // ---- helpers -----------------------------------------------------------

    private static String srcExtension(String src) {
        String name = Path.of(src).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "noext" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static int envInt(String key, int fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v.trim());
    }
}
