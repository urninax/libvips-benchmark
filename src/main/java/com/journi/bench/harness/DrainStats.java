package com.journi.bench.harness;

public record DrainStats(
    String variantName,
    int workers,
    int queueSize,
    Stats.Summary drainSeconds,
    double throughputPerSec,
    /**
     * Peak RSS of the process running this variant, in KB, minus its own bare-JVM-startup
     * baseline (see DrainHarness). Trustworthy per-variant total as of Main's subprocess-per-
     * variant isolation - each variant gets its own fresh child JVM, so this can no longer be
     * contaminated by whatever an earlier variant left resident in a shared process.
     */
    long peakSelfRssKb,
    long peakChildrenRssKb,
    /** peakSelfRssKb + peakChildrenRssKb: the real total-memory figure for this variant, comparable across every engine. */
    long peakTotalRssKb,
    long vmHwmKb,
    /**
     * Peak of libvips' own vips_tracked_get_mem() during this variant's measured reps, in KB;
     * -1 when the symbol did not resolve. A useful secondary figure for the vips variants - it
     * isolates "how much image data libvips itself is holding" - but UNDERCOUNTS true memory
     * (misses libjpeg/libpng/libwebp's own internal scratch buffers, see VipsTrackedMemory's
     * doc), so prefer {@link #peakTotalRssKb} for an actual total. ~0 for GM/IM, which never
     * call into libvips.
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
