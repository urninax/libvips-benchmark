package com.journi.bench.local;

import com.journi.bench.harness.Stats;
import com.journi.bench.metrics.RssSampler;
import com.journi.bench.metrics.VipsTrackedMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The warmup-then-measure loop shared by every local (non-S3) benchmark in this package.
 *
 * Extracted from LocalResizeBenchmark so LocalMetadataBenchmark measures with BYTE-IDENTICAL
 * methodology rather than a second, subtly different copy. The methodology is the load-bearing
 * part of these benchmarks - warmup placement, when the RSS baseline is captured, and what sits
 * inside the timed region are exactly the things that made earlier versions of this project
 * publish wrong numbers - so there must be one implementation of it, not one per benchmark.
 */
final class LocalMeasure {

    private LocalMeasure() {}

    /**
     * Keeps a reference to whatever each case produced so the JIT cannot delete the work.
     *
     * This hazard is specific to IN-PROCESS cases and has no analogue in the forked ones: a
     * subprocess always runs, but `VImage.newFromFile(...).getString(...)` whose result is never
     * used is dead code, and C2 is entitled to remove it after enough iterations. A case that
     * "takes 0.0ms" because it was optimised away is the in-process equivalent of the operation
     * cache making vips look free. Non-final and volatile so no compiler can constant-fold it.
     */
    @SuppressWarnings("unused")
    static volatile Object blackhole;

    static List<HyperfineExport.CaseResult> measureAll(List<LocalCase> cases, int warmup, int measured,
                                                       RssSampler rss, VipsTrackedMemory vipsMem,
                                                       boolean procAvailable) throws Exception {
        List<HyperfineExport.CaseResult> out = new ArrayList<>();
        int i = 0;
        for (LocalCase c : cases) {
            System.out.printf(Locale.ROOT, "Benchmark %d: %s%n", ++i, c.name());
            out.add(measure(c, warmup, measured, rss, vipsMem, procAvailable));
            HyperfineExport.CaseResult r = out.get(out.size() - 1);
            Stats.Summary s = Stats.summarize(r.seconds());
            System.out.printf(Locale.ROOT, "  Time (mean ± σ):  %8.1f ms ± %5.1f ms    Range: %.1f … %.1f ms  (%d runs)%n%n",
                s.mean() * 1000, s.stddev() * 1000, s.min() * 1000, s.max() * 1000, s.n());
        }
        return out;
    }

    static HyperfineExport.CaseResult measure(LocalCase c, int warmup, int measured,
                                              RssSampler rss, VipsTrackedMemory vipsMem,
                                              boolean procAvailable) throws Exception {
        List<Double> warmupTimes = new ArrayList<>();
        for (int i = 0; i < warmup; i++) {
            long t0 = System.nanoTime();
            c.action().run();
            warmupTimes.add((System.nanoTime() - t0) / 1e9);
        }

        // Baseline AFTER warmup: resetPeaks() only zeroes tracking counters, it cannot make the OS
        // give back heap this JVM already grew into, so without subtracting a baseline a case that
        // happens to run later silently reports memory it never needed.
        System.gc();
        Thread.sleep(50);
        long baselineSelfKb = rss.currentSelfKb();
        rss.resetPeaks();
        vipsMem.resetPeak();

        List<Double> times = new ArrayList<>();
        for (int i = 0; i < measured; i++) {
            // Everything that matters is inside this region and nothing else is. libvips is lazily
            // evaluated: thumbnail/embed only build an operation graph (~0ms) and ALL decode/resize/
            // encode work happens when the save call pulls the pipeline. Timing the stages
            // separately is forbidden - it either reports a meaningless "thumbnail = 0.02ms", or,
            // if evaluation is forced between stages, defeats pipeline fusion and measures a
            // slower pipeline that only exists because it was instrumented.
            long t0 = System.nanoTime();
            c.action().run();
            times.add((System.nanoTime() - t0) / 1e9);
        }

        long selfKb = -1, childrenKb = -1;
        if (procAvailable) {
            selfKb = Math.max(0, rss.peakSelfKb() - baselineSelfKb);
            childrenKb = rss.peakChildrenKb();
        }
        long outBytes = -1;
        try {
            // Null output is legitimate - a metadata case produces no file, only a parsed record.
            if (c.output() != null && Files.isRegularFile(c.output())) outBytes = Files.size(c.output());
        } catch (IOException ignored) {
        }
        return new HyperfineExport.CaseResult(c.name(), c.engine(), times, warmupTimes,
            selfKb, childrenKb, vipsMem.peakKb(), outBytes);
    }
}
