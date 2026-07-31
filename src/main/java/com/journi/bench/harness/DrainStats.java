package com.journi.bench.harness;

public record DrainStats(
    String variantName,
    int workers,
    int queueSize,
    Stats.Summary drainSeconds,
    double throughputPerSec,
    long peakSelfRssKb,
    long peakChildrenRssKb,
    long peakTotalRssKb,
    long vmHwmKb,
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
