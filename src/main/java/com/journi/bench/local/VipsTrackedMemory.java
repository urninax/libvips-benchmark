package com.journi.bench.local;

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
final class VipsTrackedMemory implements Runnable {

    private static final MethodHandle GET_MEM = resolveGetMem();

    private final long intervalNanos;
    private volatile boolean running = true;
    private volatile long peakBytes = 0;

    VipsTrackedMemory(long intervalMillis) {
        this.intervalNanos = intervalMillis * 1_000_000L;
    }

    /** True when the symbol resolved - otherwise every reading is -1 and callers should say "n/a". */
    static boolean available() {
        return GET_MEM != null;
    }

    static long currentBytes() {
        if (GET_MEM == null) return -1;
        try {
            return (long) GET_MEM.invokeExact();
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

    void stop() {
        running = false;
    }

    void resetPeak() {
        peakBytes = 0;
    }

    long peakKb() {
        return GET_MEM == null ? -1 : peakBytes / 1024;
    }

    private static MethodHandle resolveGetMem() {
        try {
            // Arena.global(): the handle lives as long as the JVM, matching libvips itself.
            SymbolLookup lookup = VipsLibLookup.buildSymbolLoader(Arena.global());
            return lookup.find("vips_tracked_get_mem")
                .map(addr -> Linker.nativeLinker().downcallHandle(
                    addr, FunctionDescriptor.of(ValueLayout.JAVA_LONG))) // size_t
                .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
