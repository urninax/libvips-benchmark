package com.journi.bench.metrics;

import app.photofox.vipsffm.VipsLibLookup;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * Per-case peak of libvips' OWN allocation counter, {@code vips_tracked_get_mem()}.
 *
 * WHY NOT JUST USE RSS: the JVM's resident set is useless for attributing memory to an
 * individual in-process case. `free()` does not return memory to the OS - glibc keeps it in
 * the allocator's free lists - so the first vips case grows RSS, and every later case takes
 * its baseline at that already-elevated level, reuses the same free lists, barely grows, and
 * reports ~0 MB. That is not "libvips used no memory", it is "the measurement cannot see it".
 * Subprocess engines don't have this problem (every rep is a fresh child that the kernel
 * accounts for and reaps), which is exactly why RSS stays the right metric for GM/IM and the
 * wrong one for vips.
 *
 * {@code vips_tracked_get_mem()} is a LIVE counter of currently-tracked libvips allocations,
 * not a high-water mark, so unlike RSS it drops back when an operation finishes and can
 * therefore be peaked per case. Sampling its max across a case gives the peak concurrent
 * libvips allocation for that operation - allocator-independent, and unaffected by whatever
 * earlier cases left lying around.
 *
 * CAVEAT to carry into any report: this counts allocations made through libvips'
 * vips_tracked_malloc, which covers image buffers but not everything a codec allocates
 * internally (libjpeg/libpng/libwebp scratch may not route through it). So it UNDERCOUNTS
 * total process memory and is not the same quantity as a GM child's full RSS. It answers
 * "how much image data does libvips hold at once", which is the number that matters for
 * sizing concurrency, and it is the metric libvips itself exposes for the purpose.
 *
 * vips-ffm 1.9.8 has no binding for this (neither VipsHelper nor the generated raw layer), so
 * the symbol is resolved directly via FFM, reusing the library's own lookup so it binds the
 * exact same libvips the rest of the benchmark is calling.
 */
public final class VipsTrackedMemory implements Runnable {

    private static final MethodHandle GET_MEM = resolve("vips_tracked_get_mem");

    /**
     * {@code vips_tracked_get_mem_highwater()} - libvips' EXACT peak-ever tracked allocation.
     * Confirmed present in the shipped library ({@code nm -gU libvips.42.dylib | grep tracked}).
     *
     * Deliberately NOT used as the per-case metric, even though it is exact and the sampled
     * counter is only an estimate: it is monotonic for the life of the process and libvips exposes
     * no way to reset it, so after the first case every later case's "peak" would be whatever the
     * largest case so far happened to be. Sampling the live counter is the only way to get a
     * genuinely per-case figure.
     *
     * It IS the right tool for auditing that sampler, which is what LocalMeasure's coverage gate
     * does: if the sampled maximum is well below the exact high-water delta over the same window,
     * the sampling interval is too coarse and the vipsBuf column is understated.
     */
    private static final MethodHandle GET_MEM_HIGHWATER = resolve("vips_tracked_get_mem_highwater");

    private final long intervalNanos;
    private volatile boolean running = true;
    private volatile long peakBytes = 0;

    public VipsTrackedMemory(long intervalMillis) {
        this.intervalNanos = intervalMillis * 1_000_000L;
    }

    /** True when the symbol resolved - otherwise every reading is -1 and callers should say "n/a". */
    public static boolean available() {
        return GET_MEM != null;
    }

    public static long currentBytes() {
        return call(GET_MEM);
    }

    /** Exact peak-ever tracked allocation for this process, or -1 if the symbol did not resolve. Monotonic - see GET_MEM_HIGHWATER. */
    public static long highwaterBytes() {
        return call(GET_MEM_HIGHWATER);
    }

    private static long call(MethodHandle h) {
        if (h == null) return -1;
        try {
            return (long) h.invokeExact();
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public void run() {
        while (running) {
            long now = currentBytes();
            if (now > peakBytes) peakBytes = now;
            LockSupport.parkNanos(intervalNanos);
        }
    }

    public void stop() {
        running = false;
    }

    public void resetPeak() {
        peakBytes = 0;
    }

    public long peakKb() {
        return GET_MEM == null ? -1 : peakBytes / 1024;
    }

    private static MethodHandle resolve(String symbol) {
        try {
            // Arena.global(): the handle lives as long as the JVM, matching libvips itself.
            SymbolLookup lookup = VipsLibLookup.buildSymbolLoader(Arena.global());
            return lookup.find(symbol)
                .map(addr -> Linker.nativeLinker().downcallHandle(
                    addr, FunctionDescriptor.of(ValueLayout.JAVA_LONG))) // size_t
                .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
