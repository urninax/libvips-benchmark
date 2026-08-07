package com.journi.bench.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Peak memory tracker via /proc (Linux-only - benchmark only runs in Docker).
 * Tracks the JVM's own PID plus any registered child PIDs (GM/IM subprocesses)
 * separately, since for Variant A the split between JVM-heap-holding-buffers
 * and native gm/convert memory is itself part of the finding.
 *
 * Deliberately NOT using `ps -eo rss,comm | awk '/gm|convert|vips/'` like the
 * earlier bash benchmarks - that double-counts shared pages across many gm
 * copies and can false-match unrelated processes. Explicit PID tracking via
 * Process.pid() is both narrower and correct.
 *
 * READS PSS (proportional set size), NOT RAW RSS - method/field names below keep
 * "Rss" for continuity with DrainStats/ResultTable, but the underlying /proc read is
 * smaps_rollup's "Pss:", falling back to status's "VmRSS:" only if smaps_rollup isn't
 * available. This matters specifically for GM/IM: their "children" figure sums 8
 * separate `gm`/`convert` processes, and RSS counts a shared library page (libjpeg,
 * libGraphicsMagick, glibc itself) in FULL for every process that maps it - so summing
 * raw VmRSS across 8 sibling processes overcounts shared pages by roughly 8x relative
 * to the physical memory actually resident. PSS instead divides each shared page by
 * the number of processes mapping it, so 8 children get charged their proportional
 * SHARE of a shared page, not the whole thing 8 times over. vips runs as a single
 * process, so this correction barely moves its own number (nothing to double-count) -
 * this exists specifically so GM/IM's multi-process concurrency model isn't penalized
 * by a measurement artifact relative to vips' single-process one.
 */
public final class RssSampler implements Runnable {

    private final long selfPid = ProcessHandle.current().pid();
    private final Set<Long> childPids = ConcurrentHashMap.newKeySet();
    private final long intervalNanos;

    private volatile boolean running = true;
    private volatile long peakSelfKb = 0;
    private volatile long peakChildrenKb = 0;
    private volatile long peakTotalKb = 0;

    public RssSampler(long intervalMillis) {
        this.intervalNanos = intervalMillis * 1_000_000L;
    }

    public void registerChild(long pid) {
        childPids.add(pid);
    }

    public void unregisterChild(long pid) {
        childPids.remove(pid);
    }

    @Override
    public void run() {
        while (running) {
            long self = readMemKb(selfPid);
            long children = 0;
            for (Long pid : List.copyOf(childPids)) {
                long kb = readMemKb(pid);
                if (kb < 0) {
                    childPids.remove(pid); // exited between snapshot and read
                } else {
                    children += kb;
                }
            }
            if (self > 0) peakSelfKb = Math.max(peakSelfKb, self);
            peakChildrenKb = Math.max(peakChildrenKb, children);
            peakTotalKb = Math.max(peakTotalKb, Math.max(self, 0) + children);
            LockSupport.parkNanos(intervalNanos);
        }
    }

    public void stop() {
        running = false;
    }

    public void resetPeaks() {
        peakSelfKb = 0;
        peakChildrenKb = 0;
        peakTotalKb = 0;
    }

    /**
     * Exact high-water mark for the JVM itself (kernel-tracked, immune to sampling frequency).
     * UNLIKE peakSelfKb/peakChildrenKb/peakTotalKb above, this is real RSS (the kernel has no
     * PSS-based high-water-mark counter) - not directly comparable to the PSS-based figures.
     */
    public long vmHwmKb() {
        return readVmHwmKb(selfPid);
    }

    public long peakSelfKb() { return peakSelfKb; }
    public long peakChildrenKb() { return peakChildrenKb; }
    public long peakTotalKb() { return peakTotalKb; }

    /**
     * Current (not peak) self RSS, read directly rather than via the sampler
     * thread's tracked high-water mark. Used to snapshot a baseline immediately
     * before a variant's measured reps start - {@link #resetPeaks()} only zeroes
     * the tracking counters, it can't make the OS reclaim JVM heap/metaspace
     * already grown by whatever variant ran before this one in the same
     * process, so without subtracting this baseline, a later variant's "peak"
     * silently includes memory it never actually needed.
     */
    public long currentSelfKb() {
        long kb = readMemKb(selfPid);
        return kb > 0 ? kb : 0;
    }

    /** PSS via smaps_rollup's "Pss:", falling back to status's "VmRSS:" if smaps_rollup isn't available (older kernel, /proc restricted). */
    private static long readMemKb(long pid) {
        long pss = readSmapsRollupField(pid, "Pss:");
        return pss >= 0 ? pss : readStatusField(pid, "VmRSS:");
    }

    private static long readVmHwmKb(long pid) {
        return readStatusField(pid, "VmHWM:");
    }

    private static long readSmapsRollupField(long pid, String field) {
        Path p = Path.of("/proc", String.valueOf(pid), "smaps_rollup");
        try {
            for (String line : Files.readAllLines(p)) {
                if (line.startsWith(field)) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (IOException | NumberFormatException e) {
            return -1; // smaps_rollup unavailable (kernel < 4.14, or /proc access restricted)
        }
        return -1;
    }

    private static long readStatusField(long pid, String field) {
        Path p = Path.of("/proc", String.valueOf(pid), "status");
        try {
            for (String line : Files.readAllLines(p)) {
                if (line.startsWith(field)) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (IOException | NumberFormatException e) {
            return -1; // process exited, or /proc unavailable (non-Linux)
        }
        return -1;
    }
}
