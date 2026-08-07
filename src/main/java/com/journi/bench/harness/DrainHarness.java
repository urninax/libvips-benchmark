package com.journi.bench.harness;

import com.journi.bench.BenchConfig;
import com.journi.bench.metrics.CpuSampler;
import com.journi.bench.metrics.GcSampler;
import com.journi.bench.metrics.RssSampler;
import com.journi.bench.metrics.VipsTrackedMemory;
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
    private final VipsTrackedMemory vipsMem;
    private final CpuSampler cpuSampler = new CpuSampler();
    private final GcSampler gcSampler = new GcSampler();

    public DrainHarness(RssSampler rssSampler, VipsTrackedMemory vipsMem) {
        this.rssSampler = rssSampler;
        this.vipsMem = vipsMem;
    }

    public DrainStats run(Variant variant, BenchConfig cfg, List<String> keys) throws Exception {
        variant.setUp(cfg);
        int workers = variant.workerCount(cfg);
        int totalReps = cfg.warmupReps() + cfg.measuredReps();

        // This baseline is now trustworthy - Main only ever calls run() ONCE per process (each
        // variant is its own child JVM, spawned fresh by Main.runIsolated() - see its doc). That
        // was NOT always true: this harness used to run all six variants back-to-back inside one
        // shared JVM, and since free() does not return memory to the OS (glibc keeps it in the
        // allocator's free lists), a later variant's baseline would already sit at whatever level
        // an EARLIER variant left behind - reusing those same free lists, barely growing RSS, and
        // reporting a near-zero peak that had nothing to do with how much memory the variant
        // actually used. Per-process isolation removes that failure mode entirely: this baseline
        // is just this variant's own bare JVM startup cost (classloading, thread stacks, initial
        // metaspace), captured before any of its own work has run.
        //
        // vipsTrackedPeakKb remains a useful secondary figure for the vips variants - it's
        // libvips' own live allocation counter, so it isolates "how much image data libvips
        // itself is holding" even from JVM heap noise - but it is no longer the ONLY trustworthy
        // number; peakSelfKb/peakTotalKb below are real per-variant totals now, for every engine.
        System.gc();
        Thread.sleep(50);
        long baselineSelfKb = rssSampler.currentSelfKb();

        List<Double> drainSecondsMeasured = new ArrayList<>();
        List<JobResult> measuredResults = Collections.synchronizedList(new ArrayList<>());
        long peakSelfKb = 0, peakChildrenKb = 0, peakTotalKb = 0, vmHwmKb = 0;
        long vipsTrackedPeakKb = -1;
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

                if (measured) {
                    rssSampler.resetPeaks();
                    vipsMem.resetPeak();
                }
                GcSampler.Snapshot gcBefore = gcSampler.snapshot();
                long cpuBefore = cpuSampler.jvmProcessCpuNanos() + cpuSampler.reapedChildrenCpuNanos();

                AtomicLong completed = new AtomicLong(0);
                // Distinct destination per JOB, not per (key, rep). expandToQueueSize() cycles a
                // handful of base keys to fill the queue, so with the default 2 fixtures and
                // queueSize=cores*2 the old `key + ".rep" + rep + ".out"` had 8 concurrent jobs
                // PUTting to the same S3 object every rep. No corruption (each multipart upload
                // has its own uploadId) but it is not what production does - production writes a
                // distinct output per input - and it exercises the store's overwrite path instead
                // of its create path.
                AtomicLong jobSeq = new AtomicLong(0);
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
                                String destKey = key + ".rep" + repIndex + ".job"
                                    + jobSeq.getAndIncrement() + ".out";
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
                    vipsTrackedPeakKb = Math.max(vipsTrackedPeakKb, vipsMem.peakKb());
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

        // keys.size() / drainStats.mean() counted failed jobs as completed work - a variant
        // that fails fast (workers hit an error and move on instead of doing real work) looked
        // FASTER than one that actually finishes every job. Use completed successes over the
        // summed wall time of the measured reps instead, so a failing variant's throughput
        // reflects the work it actually did, not the queue it burned through.
        double totalDrainSeconds = drainSecondsMeasured.stream().mapToDouble(Double::doubleValue).sum();
        double throughput = totalDrainSeconds > 0 ? latenciesMs.size() / totalDrainSeconds : 0;

        double cpuUtilPercent = wallNanosTotal > 0
            ? 100.0 * cpuNanosTotal / ((double) wallNanosTotal * cfg.cores())
            : 0;

        // Subtract the bare-JVM-startup baseline so peakSelfKb/peakTotalKb reflect what THIS
        // variant's measured reps actually added on top of classloading/thread-stack/metaspace
        // overhead common to every variant, not the variant's raw process footprint (see the
        // baseline capture above - now trustworthy since each variant gets its own process).
        // Children are this variant's own forked processes - never present at baseline - so
        // peakChildrenKb needs no adjustment.
        long peakSelfKbIsolated = Math.max(0, peakSelfKb - baselineSelfKb);
        long peakTotalKbIsolated = Math.max(peakChildrenKb, peakTotalKb - baselineSelfKb);

        return new DrainStats(
            variant.name(), workers, keys.size(),
            drainStats, throughput,
            peakSelfKbIsolated, peakChildrenKb, peakTotalKbIsolated, vmHwmKb, vipsTrackedPeakKb,
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
