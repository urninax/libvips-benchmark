package com.journi.bench.harness;

import java.util.List;

/** stdout ranked-summary table, styled after the earlier bash benchmarks' output format so it reads without a new mental model. */
public final class ResultTable {

    private ResultTable() {}

    // Header and data rows share these exact field widths, column for column,
    // so labels stay aligned over their data regardless of value magnitude.
    // The previous version baked column labels into literal header text (e.g.
    // "peakRSS(MB) self/child/total") with no matching width per data field,
    // so anything wider than whatever value happened to line up by luck - a
    // 6-digit RSS, a 4-digit percentile - silently drifted the columns apart.
    private static final String HEADER_FMT =
        "%-46s %7s %6s %9s %7s %11s %8s %7s %8s %10s %7s %5s %7s %8s %8s %8s %8s %8s %5s%n";
    private static final String ROW_FMT =
        "%-46s %7d %6d %9.2f %7.2f %11.2f %8.1f %7.1f %8.1f %10.1s %7.1f %5d %7d %8.2f %8.1f %8.1f %8.1f %8.1f %5d%s%n";

    /** Prints the section table with its column-glossary footer. Called exactly twice per run (Main.run()'s two final section summaries) - Main.runVariant() no longer prints its own per-variant table, since these two calls already cover every variant. */
    public static void print(String sectionTitle, List<DrainStats> results) {
        System.out.println();
        System.out.println("=== " + sectionTitle + " ===");
        System.out.printf(HEADER_FMT,
            "variant", "workers", "queue", "drain(s)", "±sd", "thru/s",
            "totalMB", "MB/thru", "rssChild", "vipsPeak", "cpu%", "gc(n)", "gc(ms)",
            "overlap", "p50ms", "p95ms", "p99ms", "maxms", "fail");
        for (DrainStats r : results) {
            System.out.printf(ROW_FMT,
                r.variantName(), r.workers(), r.queueSize(),
                r.drainSeconds().mean(), r.drainSeconds().stddev(),
                r.throughputPerSec(),
                r.peakTotalRssKb() / 1024.0,
                r.throughputPerSec() > 0 ? (r.peakTotalRssKb() / 1024.0) / r.throughputPerSec() : 0.0,
                r.peakChildrenRssKb() / 1024.0,
                r.vipsTrackedPeakKb() < 0 ? "n/a" : String.format("%.1f", r.vipsTrackedPeakKb() / 1024.0),
                r.cpuUtilPercent(),
                r.gcCount(), r.gcPauseMs(),
                r.avgOverlapRatio(),
                r.p50Ms(), r.p95Ms(), r.p99Ms(), r.maxMs(),
                r.failureCount(),
                r.failureCount() > 0 ? "  [FIRST ERROR: " + r.firstError() + "]" : "");
        }
        System.out.println("  drain(s)/±sd: mean/stddev drain time across measured reps; thru/s: successful keys drained");
        System.out.println("  per second of measured wall time (failed jobs are NOT counted as throughput).");
        System.out.println("  GM/IM 'process' time includes a `gm identify`/metadata-read fork before the resize -");
        System.out.println("  vips gets dimensions free from the decode header, so this column is not resize-only for GM/IM.");
        System.out.println("  totalMB: peak total memory (harness process + any forked children) for THIS variant alone -");
        System.out.println("  trustworthy for every engine since each variant now runs in its own isolated child JVM");
        System.out.println("  (see Main.runIsolated()), so it can't be contaminated by memory an earlier variant left behind.");
        System.out.println("  MB/thru = totalMB / (thru/s): memory needed to sustain this rate of throughput - the right column");
        System.out.println("  for a memory-EFFICIENCY claim between engines, since a faster engine that does more work in the");
        System.out.println("  same peak-memory window earns a lower number here even at equal totalMB. Deliberately NOT");
        System.out.println("  totalMB/(jobs completed) - that has no time term, so it shrinks arbitrarily as measuredReps/");
        System.out.println("  queueSize grow even though the actual peak concurrency never changes; dividing by throughput");
        System.out.println("  (jobs/TIME) keeps the time and volume dimensions correctly coupled.");
        System.out.println("  totalMB/rssChild are PSS (proportional set size), NOT raw RSS - GM/IM's children are 8 separate");
        System.out.println("  processes mapping the same shared libraries (libjpeg, libGraphicsMagick, glibc); summing raw RSS");
        System.out.println("  across siblings would count each shared page once PER PROCESS (~8x), while vips' single process");
        System.out.println("  never had that inflation - PSS divides shared pages by mapper count so neither engine's number is");
        System.out.println("  penalized by its own concurrency model (multi-process vs multi-threaded-in-one-process).");
        System.out.println("  rssChild (MB): peak (PSS) memory of forked gm/convert children specifically, meaningful for GM/IM rows.");
        System.out.println("  vipsPeak (MB): peak of libvips' own vips_tracked_get_mem() - a secondary, vips-only figure that");
        System.out.println("  UNDERCOUNTS true memory (misses libjpeg/libpng/libwebp's own scratch buffers); use totalMB for");
        System.out.println("  an actual total, this for \"how much image data is libvips itself holding\".");
        System.out.println("  cpu%: JVM+children CPU time / (wall time * cores)");
        System.out.println("  overlap = (fetch+process+upload)/total; >1.0 means I/O overlapped compute (streaming variants only)");
        System.out.println("  p50/p95/p99/max in ms, pooled over " + (results.isEmpty() ? 0 : results.get(0).latencySampleCount()) + " samples (measured reps x queue size)");
    }
}
