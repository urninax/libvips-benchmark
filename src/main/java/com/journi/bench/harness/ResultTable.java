package com.journi.bench.harness;

import java.util.List;

/** stdout ranked-summary table, styled after the earlier bash benchmarks' output format so it reads without a new mental model. */
public final class ResultTable {

    private ResultTable() {}

    public static void print(String sectionTitle, List<DrainStats> results) {
        System.out.println();
        System.out.println("=== " + sectionTitle + " ===");
        System.out.printf(
            "%-42s workers=%-4s queue=%-4s drain(s)=%-16s throughput=%-9s peakRSS(MB) self/child/total   cpu%%   gc(n/ms)  overlap  p50/p95/p99/max(ms)  fail%n",
            "variant", "", "", "mean±sd", "/s");
        for (DrainStats r : results) {
            System.out.printf(
                "%-42s %-11d %-9d %6.2f±%-6.2f %9.2f      %6.1f/%6.1f/%7.1f    %5.1f  %3d/%-6d %6.2f   %6.1f/%6.1f/%6.1f/%6.1f  %d%s%n",
                r.variantName(), r.workers(), r.queueSize(),
                r.drainSeconds().mean(), r.drainSeconds().stddev(),
                r.throughputPerSec(),
                r.peakSelfRssKb() / 1024.0, r.peakChildrenRssKb() / 1024.0, r.peakTotalRssKb() / 1024.0,
                r.cpuUtilPercent(),
                r.gcCount(), r.gcPauseMs(),
                r.avgOverlapRatio(),
                r.p50Ms(), r.p95Ms(), r.p99Ms(), r.maxMs(),
                r.failureCount(),
                r.failureCount() > 0 ? "  [FIRST ERROR: " + r.firstError() + "]" : "");
        }
        System.out.println("  overlap = (fetch+process+upload)/total; >1.0 means I/O overlapped compute (streaming variants only)");
        System.out.println("  percentiles pooled over " + (results.isEmpty() ? 0 : results.get(0).latencySampleCount()) + " samples (measured reps x queue size)");
    }
}
