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

    /**
     * Snapshot of the two EXACT, sampling-immune memory counters, taken before the first case so
     * the coverage gate can report growth rather than absolute values.
     */
    record MemorySnapshot(long vipsHighwaterBytes, long vmHwmKb) {
        static MemorySnapshot take(RssSampler rss) {
            return new MemorySnapshot(VipsTrackedMemory.highwaterBytes(), rss.vmHwmKb());
        }
    }

    /**
     * Reports what fraction of real process memory the vipsBuf column actually accounts for, and
     * audits the sampler that produces it.
     *
     * WHY THIS IS PRINTED RATHER THAN LEFT AS A DOC CAVEAT: vipsBuf and rssChildren sit in adjacent
     * columns and look like peers. They are not. vipsBuf counts only allocations routed through
     * vips_tracked_malloc - image buffers - while rssChildren counts a forked child's ENTIRE
     * resident set: buffers, codec scratch, library code and data, glibc, stack. Measured on the
     * mobileFull case, libvips-tracked memory was 10.4 MB against 31.0 MB of actual process growth,
     * i.e. the column sees about a third of what it is being compared against. Reading the two
     * columns as a ratio therefore overstates vips' memory advantage by roughly 3x.
     *
     * That vips wins on memory is not in question and this gate does not dispute it - it never
     * forks, so it never pays per-image process setup or re-maps its own libraries, and that is a
     * permanent architectural advantage rather than a measurement artifact. The gate exists so the
     * MARGIN quoted from this table is the real one.
     *
     * The sampler audit is the second half: vips_tracked_get_mem_highwater() is exact, so if the
     * sampled maximum falls well short of the high-water delta over the same window, the 5ms
     * interval missed peaks and vipsBuf is understated on top of everything else. (Verified once
     * by hand at 20 reps: sampled max matched the exact high-water to the byte, because peaking
     * across repeated identical reps recovers what any single rep might miss.)
     */
    static void printMemoryCoverage(MemorySnapshot before, RssSampler rss,
                                    List<HyperfineExport.CaseResult> results) {
        long sampledMaxKb = results.stream()
            .filter(r -> "vips".equals(r.engine()))
            .mapToLong(HyperfineExport.CaseResult::vipsTrackedPeakKb)
            .filter(kb -> kb >= 0)
            .max().orElse(-1);

        long hwAfter = VipsTrackedMemory.highwaterBytes();
        long vmHwmAfterKb = rss.vmHwmKb();
        long hwDeltaBytes = (hwAfter >= 0 && before.vipsHighwaterBytes() >= 0)
            ? hwAfter - before.vipsHighwaterBytes() : -1;
        long vmGrowthKb = (vmHwmAfterKb >= 0 && before.vmHwmKb() >= 0)
            ? vmHwmAfterKb - before.vmHwmKb() : -1;

        System.out.print(renderCoverage(sampledMaxKb, hwDeltaBytes, vmGrowthKb));
    }

    /**
     * Pure rendering of the coverage block, split out from the /proc and libvips reads so the
     * arithmetic and every branch can be exercised without a Linux container or a live libvips.
     * All three inputs use -1 for "unavailable".
     */
    static String renderCoverage(long sampledMaxKb, long hwDeltaBytes, long vmGrowthKb) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%n---- memory-metric coverage ----%n"));

        if (sampledMaxKb < 0) {
            sb.append("no in-process vips cases with a usable vipsBuf reading — nothing to audit\n");
            return sb.toString();
        }
        double sampledMb = sampledMaxKb / 1024.0;
        sb.append(String.format(Locale.ROOT, "  vipsBuf, max sampled across vips cases   : %8.1f MB%n", sampledMb));

        if (hwDeltaBytes < 0) {
            sb.append("  vips_tracked highwater delta (EXACT)     :      n/a  (symbol did not resolve)\n");
        } else {
            double hwDeltaMb = hwDeltaBytes / (1024.0 * 1024.0);
            sb.append(String.format(Locale.ROOT, "  vips_tracked highwater delta (EXACT)     : %8.1f MB", hwDeltaMb));
            if (hwDeltaMb > 0.01) {
                double captured = 100.0 * sampledMb / hwDeltaMb;
                sb.append(String.format(Locale.ROOT, "   [sampler captured %.1f%%]%n", captured));
                if (captured < 90.0) {
                    sb.append("  WARNING: the sampler missed a material share of the true libvips peak — the\n");
                    sb.append("    5ms interval is too coarse for these case durations, so vipsBuf is understated.\n");
                }
            } else {
                sb.append(String.format(Locale.ROOT, "%n"));
            }
        }

        if (vmGrowthKb < 0) {
            sb.append("  JVM VmHWM growth over the run            :      n/a  (needs /proc — Linux only)\n");
            sb.append("  => coverage ratio unavailable on this platform; run in the container for it.\n");
            return sb.toString();
        }

        double vmGrowthMb = vmGrowthKb / 1024.0;
        sb.append(String.format(Locale.ROOT, "  JVM VmHWM growth over the run            : %8.1f MB  (kernel-exact)%n", vmGrowthMb));
        if (vmGrowthMb <= 0.01) {
            sb.append("  => VmHWM did not grow during this run, so no coverage ratio can be formed. That\n");
            sb.append("     usually means the JVM's high-water was already set before the first case.\n");
            return sb.toString();
        }

        double coverage = 100.0 * sampledMb / vmGrowthMb;
        sb.append(String.format(Locale.ROOT,
            "  => vipsBuf accounts for %.1f%% of the process memory these cases actually caused.%n", coverage));
        sb.append("     The remainder is codec scratch (libjpeg/libpng/libwebp), glib and JVM overhead —\n");
        sb.append("     none of it routes through vips_tracked_malloc, so none of it reaches that column.\n");
        sb.append("     GM's rssChildren counts ALL of its equivalent. Do NOT read vipsBuf/rssChildren as a\n");
        sb.append(String.format(Locale.ROOT,
            "     like-for-like ratio: doing so overstates vips' memory advantage by roughly %.1fx.%n",
            coverage > 0 ? 100.0 / coverage : 0.0));
        sb.append("     (vips does still win on memory — it never forks — but by the corrected margin.)\n");
        return sb.toString();
    }

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
