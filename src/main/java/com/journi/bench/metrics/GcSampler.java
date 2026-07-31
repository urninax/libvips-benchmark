package com.journi.bench.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.List;

/** GC pause/count deltas around a measured rep - relevant now that this is a long-lived JVM process. */
public final class GcSampler {

    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<MemoryPoolMXBean> poolBeans = ManagementFactory.getMemoryPoolMXBeans();

    public record Snapshot(long collectionCount, long collectionTimeMillis) {}

    public Snapshot snapshot() {
        long count = 0, time = 0;
        for (GarbageCollectorMXBean b : gcBeans) {
            long c = b.getCollectionCount();
            long t = b.getCollectionTime();
            if (c > 0) count += c;
            if (t > 0) time += t;
        }
        return new Snapshot(count, time);
    }

    public Snapshot delta(Snapshot before, Snapshot after) {
        return new Snapshot(
            after.collectionCount() - before.collectionCount(),
            after.collectionTimeMillis() - before.collectionTimeMillis()
        );
    }

    public void resetPeakHeap() {
        for (MemoryPoolMXBean p : poolBeans) {
            if (p.isUsageThresholdSupported() || true) {
                try { p.resetPeakUsage(); } catch (UnsupportedOperationException ignored) {}
            }
        }
    }

    public long peakHeapBytes() {
        long peak = 0;
        for (MemoryPoolMXBean p : poolBeans) {
            if (p.getType() == java.lang.management.MemoryType.HEAP) {
                peak += p.getPeakUsage().getUsed();
            }
        }
        return peak;
    }
}
