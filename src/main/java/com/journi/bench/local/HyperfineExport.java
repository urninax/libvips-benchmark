package com.journi.bench.local;

import com.journi.bench.harness.Stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes results in hyperfine's own markdown/JSON shape so a run of this benchmark can be laid
 * side by side with a run of benchmark_resize.sh.
 *
 * The markdown emits hyperfine's exact 5-column table first (identical layout, so the two files
 * diff cleanly), then an EXTENDED table with the things hyperfine can't know about: median, MAD,
 * peak RSS split self-vs-children, and output size.
 *
 * The JSON deliberately stays STRICTLY VALID (unlike benchmark_resize.sh, which prepends the
 * source filename and thereby breaks `jq`). The source path is carried as a proper top-level
 * `source` field instead. Only the markdown gets the prepended source line, matching the
 * script's readable-output convention without the parse hazard.
 *
 * `user`/`system` are emitted as null, never 0.0: child CPU time is not recoverable from Java
 * after the process exits, and a literal 0.0 sitting next to a 200ms wall time reads as
 * "GM used no CPU".
 */
final class HyperfineExport {

    private HyperfineExport() {}

    /**
     * Provenance for the fixture every case ran against. Exported so a chart or a report can
     * identify the input by what actually matters (format, resolution, size, whether it carries
     * an ICC profile) rather than by a filename that means nothing to a reader.
     */
    record SourceInfo(String path, String format, int width, int height, long bytes, int iccBytes) {}

    /** All times in SECONDS (hyperfine's unit). rss values in KB; -1 means "not measured". */
    record CaseResult(
        String name,
        String engine,
        List<Double> seconds,
        List<Double> warmupSeconds,
        long rssSelfKb,
        long rssChildrenKb,
        long vipsTrackedPeakKb,
        long outputBytes
    ) {}

    static void write(Path mdFile, Path jsonFile, SourceInfo source, String title,
                      List<CaseResult> results) throws IOException {
        Files.createDirectories(mdFile.getParent());
        Files.writeString(mdFile, describe(source) + "\n\n" + markdown(title, results));
        Files.writeString(jsonFile, json(source, results));
    }

    private static String describe(SourceInfo s) {
        return String.format(Locale.ROOT, "%s — %s %dx%d, %.1f MB, ICC %s",
            s.path(), s.format().toUpperCase(Locale.ROOT), s.width(), s.height(),
            s.bytes() / (1024.0 * 1024.0),
            s.iccBytes() < 0 ? "absent" : s.iccBytes() + " bytes");
    }

    // ---- markdown ----------------------------------------------------------

    private static String markdown(String title, List<CaseResult> results) {
        StringBuilder sb = new StringBuilder();
        if (results.isEmpty()) {
            return "_(no cases ran - check BENCH_ENGINES)_\n";
        }

        List<Stats.Summary> summaries = new ArrayList<>();
        for (CaseResult r : results) summaries.add(Stats.summarize(r.seconds()));
        double fastestMean = summaries.stream().mapToDouble(Stats.Summary::mean).min().orElse(0);
        int fastestIdx = 0;
        for (int i = 0; i < summaries.size(); i++) {
            if (summaries.get(i).mean() == fastestMean) { fastestIdx = i; break; }
        }
        Stats.Summary base = summaries.get(fastestIdx);

        // hyperfine-identical table
        sb.append("| Command | Mean [ms] | Min [ms] | Max [ms] | Relative |\n");
        sb.append("|:---|---:|---:|---:|---:|\n");
        for (int i = 0; i < results.size(); i++) {
            Stats.Summary s = summaries.get(i);
            String relative;
            if (i == fastestIdx) {
                relative = "1.00";
            } else {
                double ratio = s.mean() / base.mean();
                // hyperfine's error propagation for a ratio of two means
                double err = ratio * Math.sqrt(
                    sq(s.stddev() / s.mean()) + sq(base.stddev() / base.mean()));
                relative = fmt(ratio, 2) + " ± " + fmt(err, 2);
            }
            sb.append(String.format(Locale.ROOT, "| `%s` | %s ± %s | %s | %s | %s |%n",
                results.get(i).name(),
                fmt(s.mean() * 1000, 1), fmt(s.stddev() * 1000, 1),
                fmt(s.min() * 1000, 1), fmt(s.max() * 1000, 1),
                relative));
        }

        // extended table
        sb.append("\n#### ").append(title).append(" — extended\n\n");
        // "vipsBuf", NOT "vipsPeak": the old label read as a peer of rssChildren and invited
        // exactly the ratio it must not be used for. It is not a peak of anything comparable -
        // it is libvips' image BUFFERS only, measured at ~34% of real process growth (see
        // LocalMeasure.printMemoryCoverage, which now prints that ratio per run).
        sb.append("| Command | Engine | Median [ms] | MAD [ms] | n | vipsBuf [MB] | rssChildren [MB] | rssSelf [MB] | out [KB] |\n");
        sb.append("|:---|:---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (CaseResult r : results) {
            List<Double> sorted = new ArrayList<>(r.seconds());
            sorted.sort(Double::compareTo);
            double median = Stats.percentile(sorted, 0.50);
            sb.append(String.format(Locale.ROOT, "| `%s` | %s | %s | %s | %d | %s | %s | %s | %s |%n",
                r.name(), r.engine(),
                fmt(median * 1000, 1), fmt(mad(sorted, median) * 1000, 1), r.seconds().size(),
                kbToMb(r.vipsTrackedPeakKb()), kbToMb(r.rssChildrenKb()), kbToMb(r.rssSelfKb()),
                r.outputBytes() < 0 ? "n/a" : fmt(r.outputBytes() / 1024.0, 0)));
        }
        sb.append("\nMedian/MAD are the robust summary — the distribution is a hard lower bound with a\n");
        sb.append("one-sided tail of interference, so mean±sd overstates spread.\n\n");
        sb.append("MEMORY COLUMNS ARE DIFFERENT QUANTITIES — never add them, and never divide one by\n");
        sb.append("the other. `vipsBuf / rssChildren` is NOT a memory-saving ratio; measured on the\n");
        sb.append("mobileFull case it overstates vips' advantage by ~3x. The run's own\n");
        sb.append("\"memory-metric coverage\" block prints the real figure for that run.\n");
        sb.append("- `vipsBuf`: peak of libvips' own vips_tracked_get_mem() during the case — image\n");
        sb.append("  BUFFERS ONLY. Excludes libjpeg/libpng/libwebp scratch, glib, and JVM overhead,\n");
        sb.append("  which together were ~2/3 of the actual process memory the case caused.\n");
        sb.append("- `rssChildren`: peak RSS of the forked gm/convert child — the child's ENTIRE\n");
        sb.append("  resident set. The meaningful number for GM/IM rows, and a genuine per-image\n");
        sb.append("  figure since every rep forks fresh. vips legitimately avoids this cost entirely\n");
        sb.append("  by never forking; that advantage is real, only its magnitude is misread here.\n");
        sb.append("- `rssSelf`: JVM RSS growth over a pre-case baseline. UNRELIABLE for any case\n");
        sb.append("  after the first — glibc does not return freed memory to the OS, so later cases\n");
        sb.append("  reuse existing free lists and read ~0. Kept only as a diagnostic.\n");
        return sb.toString();
    }

    // ---- json --------------------------------------------------------------

    private static String json(SourceInfo src, List<CaseResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"source\": \"").append(esc(src.path())).append("\",\n");
        sb.append("  \"source_format\": \"").append(esc(src.format())).append("\",\n");
        sb.append("  \"source_width\": ").append(src.width()).append(",\n");
        sb.append("  \"source_height\": ").append(src.height()).append(",\n");
        sb.append("  \"source_bytes\": ").append(src.bytes()).append(",\n");
        sb.append("  \"source_icc_bytes\": ").append(src.iccBytes()).append(",\n");
        sb.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            CaseResult r = results.get(i);
            Stats.Summary s = Stats.summarize(r.seconds());
            List<Double> sorted = new ArrayList<>(r.seconds());
            sorted.sort(Double::compareTo);
            sb.append("    {\n");
            sb.append("      \"command\": \"").append(esc(r.name())).append("\",\n");
            sb.append("      \"engine\": \"").append(esc(r.engine())).append("\",\n");
            sb.append("      \"mean\": ").append(num(s.mean())).append(",\n");
            sb.append("      \"stddev\": ").append(num(s.stddev())).append(",\n");
            sb.append("      \"median\": ").append(num(Stats.percentile(sorted, 0.50))).append(",\n");
            sb.append("      \"mad\": ").append(num(mad(sorted, Stats.percentile(sorted, 0.50)))).append(",\n");
            sb.append("      \"user\": null,\n");
            sb.append("      \"system\": null,\n");
            sb.append("      \"min\": ").append(num(s.min())).append(",\n");
            sb.append("      \"max\": ").append(num(s.max())).append(",\n");
            sb.append("      \"rss_self_kb\": ").append(r.rssSelfKb()).append(",\n");
            sb.append("      \"rss_children_kb\": ").append(r.rssChildrenKb()).append(",\n");
            sb.append("      \"vips_tracked_peak_kb\": ").append(r.vipsTrackedPeakKb()).append(",\n");
            sb.append("      \"output_bytes\": ").append(r.outputBytes()).append(",\n");
            sb.append("      \"times\": ").append(numArray(r.seconds())).append(",\n");
            sb.append("      \"warmup_times\": ").append(numArray(r.warmupSeconds())).append(",\n");
            sb.append("      \"exit_codes\": ").append(zeros(r.seconds().size())).append("\n");
            sb.append("    }").append(i < results.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    // ---- helpers -----------------------------------------------------------

    /** Median absolute deviation - robust spread, unaffected by the right tail. */
    private static double mad(List<Double> sortedAscending, double median) {
        if (sortedAscending.isEmpty()) return 0;
        List<Double> devs = new ArrayList<>(sortedAscending.size());
        for (double v : sortedAscending) devs.add(Math.abs(v - median));
        devs.sort(Double::compareTo);
        return Stats.percentile(devs, 0.50);
    }

    private static double sq(double v) { return v * v; }

    private static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static String kbToMb(long kb) {
        return kb < 0 ? "n/a" : fmt(kb / 1024.0, 1);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.9f", v);
    }

    private static String numArray(List<Double> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(num(values.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String zeros(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) sb.append(i > 0 ? ", 0" : "0");
        return sb.append("]").toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
