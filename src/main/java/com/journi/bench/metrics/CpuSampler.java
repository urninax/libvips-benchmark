package com.journi.bench.metrics;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.management.OperatingSystemMXBean;

/**
 * CPU utilization for the JVM itself AND for spawned GM/IM child processes.
 *
 * getProcessCpuTime() only covers the JVM process - for Variant A (which does
 * almost all its real work in gm/convert subprocesses) that would make it look
 * nearly idle while it's actually pegging every core. utime+stime from
 * /proc/<pid>/stat for reaped children (via cutime+cstime accumulated on the
 * JVM after wait()) is the reliable total.
 */
public final class CpuSampler {

    private static final long CLK_TCK = 100L; // sysconf(_SC_CLK_TCK), standard on Linux
    private final OperatingSystemMXBean osBean =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public long jvmProcessCpuNanos() {
        long t = osBean.getProcessCpuTime();
        return t < 0 ? 0 : t;
    }

    /** cutime+cstime (children CPU time, accumulated on the JVM's own /proc/self/stat after wait()). */
    public long reapedChildrenCpuNanos() {
        try {
            String stat = Files.readString(Path.of("/proc/self/stat"));
            // fields are space-separated; comm field (2nd) may contain spaces/parens,
            // so parse from the last ')' onward for safety.
            int close = stat.lastIndexOf(')');
            String[] rest = stat.substring(close + 2).trim().split("\\s+");
            // after comm: state(0) ppid(1) pgrp(2) session(3) tty_nr(4) tpgid(5) flags(6)
            // minflt(7) cminflt(8) majflt(9) cmajflt(10) utime(11) stime(12) cutime(13) cstime(14)
            long cutime = Long.parseLong(rest[13]);
            long cstime = Long.parseLong(rest[14]);
            return (cutime + cstime) * (1_000_000_000L / CLK_TCK);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    public double systemLoadAverage() {
        return osBean.getSystemLoadAverage();
    }
}
