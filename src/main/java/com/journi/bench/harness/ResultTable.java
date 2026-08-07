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
        "%-46s %7s %6s %9s %7s %11s %10s %10s %7s %5s %7s %8s %8s %8s %8s %8s %5s%n";
    private static final String ROW_FMT =
        "%-46s %7d %6d %9.2f %7.2f %11.2f %10.1f %10.1s %7.1f %5d %7d %8.2f %8.1f %8.1f %8.1f %8.1f %5d%s%n";

    public static void print(String sectionTitle, List<DrainStats> results) {
        System.out.println();
        System.out.println("=== " + sectionTitle + " ===");
        System.out.printf(HEADER_FMT,
            "variant", "workers", "queue", "drain(s)", "±sd", "thru/s",
            "rssChild", "vipsPeak", "cpu%", "gc(n)", "gc(ms)",
            "overlap", "p50ms", "p95ms", "p99ms", "maxms", "fail");
        for (DrainStats r : results) {
            System.out.printf(ROW_FMT,
                r.variantName(), r.workers(), r.queueSize(),
                r.drainSeconds().mean(), r.drainSeconds().stddev(),
                r.throughputPerSec(),
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
        System.out.println("  rssChild (MB): peak RSS of forked gm/convert children, meaningful for GM/IM rows.");
        System.out.println("  vipsPeak (MB): peak of libvips' own vips_tracked_get_mem(), meaningful for vips rows -");
        System.out.println("  NOT the same quantity as rssChild, never compare them directly. peakSelfRssKb/peakTotalRssKb");
        System.out.println("  are still in the DrainStats record for backward compat but are UNRELIABLE after the first");
        System.out.println("  variant in a run (glibc does not return freed memory to the OS) and are no longer printed.");
        System.out.println("  cpu%: JVM+children CPU time / (wall time * cores)");
        System.out.println("  overlap = (fetch+process+upload)/total; >1.0 means I/O overlapped compute (streaming variants only)");
        System.out.println("  p50/p95/p99/max in ms, pooled over " + (results.isEmpty() ? 0 : results.get(0).latencySampleCount()) + " samples (measured reps x queue size)");
    }
}
