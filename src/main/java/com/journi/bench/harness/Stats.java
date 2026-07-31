package com.journi.bench.harness;

import java.util.List;

/** Sample stddev (n-1 denominator), matching hyperfine's statistic for continuity with earlier bash benchmarks. */
public final class Stats {

    public record Summary(double mean, double stddev, double min, double max, int n) {}

    public static Summary summarize(List<Double> values) {
        int n = values.size();
        if (n == 0) return new Summary(0, 0, 0, 0, 0);
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double stddev = 0;
        if (n > 1) {
            double sumSq = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
            stddev = Math.sqrt(sumSq / (n - 1));
        }
        return new Summary(mean, stddev, min, max, n);
    }

    public static double percentile(List<Double> sortedAscending, double p) {
        int n = sortedAscending.size();
        if (n == 0) return 0;
        int idx = (int) Math.ceil(p * n) - 1;
        idx = Math.max(0, Math.min(n - 1, idx));
        return sortedAscending.get(idx);
    }
}
