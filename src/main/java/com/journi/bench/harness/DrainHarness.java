package com.journi.bench.harness;

import com.journi.bench.BenchConfig;
import com.journi.bench.metrics.CpuSampler;
import com.journi.bench.metrics.GcSampler;
import com.journi.bench.metrics.RssSampler;
import com.journi.bench.variant.Variant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Repeated queue-drain benchmark within ONE JVM process (warmup + measured
 * reps share JVM startup/JIT/connection-pool warmup cost, unlike wrapping the
 * whole JVM in hyperfine per the earlier bash benchmarks).
 *
 * poll()-until-empty (not take()) on a pre-filled queue matches the burst
 * semantics of `seq | xargs -P` from the earlier bash benchmarks - workers
 * exit once the queue is drained rather than blocking for more work.
 */
public final class DrainHarness {

    // Prints a running "how far did it get" line every PROGRESS_INTERVAL_MS
    // while a rep is draining. Exists for soak/capacity tests where the queue
    // is deliberately huge (far bigger than any rep normally finishes) and the
    // process might get OOM-killed mid-drain - without this, a crash mid-rep
    // leaves the tee'd log with zero indication of how many jobs completed
    // before it died (this bit us for real: an OOM kill during the Bleed
    // phase left no trace beyond the kernel's own dmesg log). Self-throttling
    // by construction: a rep that finishes faster than one interval never
    // gets a chance to print anything, so normal small-queue runs are
    // unaffected.
    private static final long PROGRESS_INTERVAL_MS = 2000;

    private final RssSampler rssSampler;
    private final CpuSampler cpuSampler = new CpuSampler();
    private final GcSampler gcSampler = new GcSampler();

    public DrainHarness(RssSampler rssSampler) {
        this.rssSampler = rssSampler;
    }

    public DrainStats run(Variant variant, BenchConfig cfg, List<String> keys) throws Exception {
        variant.setUp(cfg);
        int workers = variant.workerCount(cfg);
        int totalReps = cfg.warmupReps() + cfg.measuredReps();

        // All variants in a `run` share one continuous JVM process (see class doc), and
        // rssSampler.resetPeaks() below only zeroes the TRACKING counters - it can't make
        // the OS give back heap/metaspace/connection-pool memory a PRIOR variant already
        // grew into. Without this baseline, a variant that happens to run later (every
        // vips variant runs after GM/IM - see Main.run()) silently reports peak RSS that
        // includes memory it never actually needed itself. System.gc() gives G1 a chance
        // to hand back what it can before we snapshot; what's left is genuinely shared,
        // unavoidable JVM baseline (this variant's own one-time setUp() cost, e.g.
        // Vips.init() loading libvips, is deliberately INSIDE this baseline too - a real
        // server pays that once at startup, not per job, so excluding it from every
        // variant's reported number is the fair comparison for capacity planning).
        System.gc();
        Thread.sleep(50);
        long baselineSelfKb = rssSampler.currentSelfKb();

        List<Double> drainSecondsMeasured = new ArrayList<>();
        List<JobResult> measuredResults = Collections.synchronizedList(new ArrayList<>());
        long peakSelfKb = 0, peakChildrenKb = 0, peakTotalKb = 0, vmHwmKb = 0;
        long gcCountTotal = 0, gcPauseMsTotal = 0;
        long cpuNanosTotal = 0;
        long wallNanosTotal = 0;

        try {
            for (int rep = 0; rep < totalReps; rep++) {
                boolean measured = rep >= cfg.warmupReps();

                BlockingQueue<String> queue = new ArrayBlockingQueue<>(keys.size());
                queue.addAll(keys);

                ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
                    Thread t = new Thread(r, "worker");
                    t.setDaemon(true);
                    return t;
                });

                List<JobResult> repResults = Collections.synchronizedList(new ArrayList<>());
                CountDownLatch done = new CountDownLatch(workers);
                int repIndex = rep;

                if (measured) rssSampler.resetPeaks();
                GcSampler.Snapshot gcBefore = gcSampler.snapshot();
                long cpuBefore = cpuSampler.jvmProcessCpuNanos() + cpuSampler.reapedChildrenCpuNanos();

                AtomicLong completed = new AtomicLong(0);
                AtomicBoolean repRunning = new AtomicBoolean(true);
                long t0 = System.nanoTime();

                Thread progress = new Thread(() -> {
                    while (repRunning.get()) {
                        try {
                            Thread.sleep(PROGRESS_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (!repRunning.get()) return;
                        System.out.printf("[progress] %s rep=%d/%d completed=%d/%d elapsed=%.1fs rssSelfMB=%.1f%n",
                                variant.name(), repIndex + 1, totalReps, completed.get(), keys.size(),
                                (System.nanoTime() - t0) / 1e9, rssSampler.currentSelfKb() / 1024.0);
                    }
                }, "progress");

                if(cfg.progressEnabled()) {
                    progress.setDaemon(true);
                    progress.start();
                }

                for (int i = 0; i < workers; i++) {
                    pool.submit(() -> {
                        try {
                            String key;
                            while ((key = queue.poll()) != null) {
                                String destKey = key + ".rep" + repIndex + ".out";
                                long jobStart = System.nanoTime();
                                JobResult result;
                                try {
                                    result = variant.process(key, destKey);
                                } catch (Exception e) {
                                    result = JobResult.failure(key, e.toString(), System.nanoTime() - jobStart);
                                }
                                repResults.add(result);
                                completed.incrementAndGet();
                            }
                        } finally {
                            done.countDown();
                        }
                    });
                }
                done.await();
                long drainNanos = System.nanoTime() - t0;
                repRunning.set(false);
                if(cfg.progressEnabled()) {
                    progress.interrupt();
                }
                pool.shutdown();
                pool.awaitTermination(30, TimeUnit.SECONDS);

                GcSampler.Snapshot gcAfter = gcSampler.snapshot();
                long cpuAfter = cpuSampler.jvmProcessCpuNanos() + cpuSampler.reapedChildrenCpuNanos();

                if (measured) {
                    drainSecondsMeasured.add(drainNanos / 1e9);
                    measuredResults.addAll(repResults);
                    peakSelfKb = Math.max(peakSelfKb, rssSampler.peakSelfKb());
                    peakChildrenKb = Math.max(peakChildrenKb, rssSampler.peakChildrenKb());
                    peakTotalKb = Math.max(peakTotalKb, rssSampler.peakTotalKb());
                    vmHwmKb = Math.max(vmHwmKb, rssSampler.vmHwmKb());
                    GcSampler.Snapshot d = gcSampler.delta(gcBefore, gcAfter);
                    gcCountTotal += Math.max(0, d.collectionCount());
                    gcPauseMsTotal += Math.max(0, d.collectionTimeMillis());
                    cpuNanosTotal += Math.max(0, cpuAfter - cpuBefore);
                    wallNanosTotal += drainNanos;
                }
            }
        } finally {
            variant.tearDown();
        }

        Stats.Summary drainStats = Stats.summarize(drainSecondsMeasured);
        double throughput = drainStats.mean() > 0 ? keys.size() / drainStats.mean() : 0;

        List<Double> latenciesMs = new ArrayList<>();
        int failures = 0;
        String firstError = null;
        double overlapSum = 0;
        int overlapCount = 0;
        for (JobResult r : measuredResults) {
            if (!r.ok()) {
                failures++;
                if (firstError == null) firstError = r.error();
                continue;
            }
            latenciesMs.add(r.totalNanos() / 1e6);
            overlapSum += r.overlapRatio();
            overlapCount++;
        }
        Collections.sort(latenciesMs);

        double cpuUtilPercent = wallNanosTotal > 0
            ? 100.0 * cpuNanosTotal / ((double) wallNanosTotal * cfg.cores())
            : 0;

        // Subtract the pre-variant baseline so peakSelfKb/peakTotalKb reflect what THIS
        // variant's measured reps actually added, not memory a prior variant already left
        // resident in the shared JVM process (see the baseline capture above). Children are
        // this variant's own forked processes - never present at baseline - so peakChildrenKb
        // needs no adjustment.
        long peakSelfKbIsolated = Math.max(0, peakSelfKb - baselineSelfKb);
        long peakTotalKbIsolated = Math.max(peakChildrenKb, peakTotalKb - baselineSelfKb);

        return new DrainStats(
            variant.name(), workers, keys.size(),
            drainStats, throughput,
            peakSelfKbIsolated, peakChildrenKb, peakTotalKbIsolated, vmHwmKb,
            Stats.percentile(latenciesMs, 0.50),
            Stats.percentile(latenciesMs, 0.95),
            Stats.percentile(latenciesMs, 0.99),
            latenciesMs.isEmpty() ? 0 : latenciesMs.get(latenciesMs.size() - 1),
            latenciesMs.size(),
            overlapCount > 0 ? overlapSum / overlapCount : 0,
            cpuUtilPercent,
            gcCountTotal, gcPauseMsTotal,
            failures, firstError
        );
    }
}
