package com.journi.bench.harness;

public record DrainStats(
    String variantName,
    int workers,
    int queueSize,
    Stats.Summary drainSeconds,
    double throughputPerSec,
    /**
     * UNRELIABLE for any variant after the first - glibc does not return freed memory to the
     * OS, so later variants baseline at the elevated level their predecessors left behind and
     * read ~0. Diagnostic only; see DrainHarness's baseline comment. Use
     * {@link #vipsTrackedPeakKb} for vips and {@link #peakChildrenRssKb} for GM/IM.
     */
    long peakSelfRssKb,
    long peakChildrenRssKb,
    long peakTotalRssKb,
    long vmHwmKb,
    /**
     * Peak of libvips' own vips_tracked_get_mem() during this variant's measured reps, in KB;
     * -1 when the symbol did not resolve. The trustworthy per-variant memory figure for the
     * in-process vips variants, and ~0 for GM/IM (which do their work in child processes).
     */
    long vipsTrackedPeakKb,
    double p50Ms,
    double p95Ms,
    double p99Ms,
    double maxMs,
    int latencySampleCount,
    double avgOverlapRatio,
    double cpuUtilPercent,
    long gcCount,
    long gcPauseMs,
    int failureCount,
    String firstError
) {}
