package com.journi.bench.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Peak RSS tracker via /proc (Linux-only - benchmark only runs in Docker).
 * Tracks the JVM's own PID plus any registered child PIDs (GM/IM subprocesses)
 * separately, since for Variant A the split between JVM-heap-holding-buffers
 * and native gm/convert RSS is itself part of the finding.
 *
 * Deliberately NOT using `ps -eo rss,comm | awk '/gm|convert|vips/'` like the
 * earlier bash benchmarks - that double-counts shared pages across many gm
 * copies and can false-match unrelated processes. Explicit PID tracking via
 * Process.pid() is both narrower and correct.
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
            long self = readVmRssKb(selfPid);
            long children = 0;
            for (Long pid : List.copyOf(childPids)) {
                long kb = readVmRssKb(pid);
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

    /** Exact high-water mark for the JVM itself (kernel-tracked, immune to sampling frequency). */
    public long vmHwmKb() {
        return readVmHwmKb(selfPid);
    }

    public long peakSelfKb() { return peakSelfKb; }
    public long peakChildrenKb() { return peakChildrenKb; }
    public long peakTotalKb() { return peakTotalKb; }

    private static long readVmRssKb(long pid) {
        return readStatusField(pid, "VmRSS:");
    }

    private static long readVmHwmKb(long pid) {
        return readStatusField(pid, "VmHWM:");
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
