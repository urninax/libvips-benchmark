package com.journi.bench.harness;

/**
 * Per-job outcome. fetch/process/upload times are attributed independently and,
 * for streaming variants, are EXPECTED to overlap (sum > total) - that overlap
 * is the direct evidence streaming actually pipelines I/O with compute.
 */
public record JobResult(
    String key,
    boolean ok,
    String error,
    long totalNanos,
    long fetchNanos,
    long processNanos,
    long uploadNanos,
    long bytesIn,
    long bytesOut,
    int outWidth,
    int outHeight
) {
    public double overlapRatio() {
        if (totalNanos <= 0) return 0;
        return (double) (fetchNanos + processNanos + uploadNanos) / totalNanos;
    }

    public static JobResult failure(String key, String error, long totalNanos) {
        return new JobResult(key, false, error, totalNanos, 0, 0, 0, 0, 0, 0, 0);
    }
}
