package com.journi.bench.local;

import java.nio.file.Path;

/**
 * One unit of measurement: a hyperfine-style named command, the engine that owns it (for
 * BENCH_ENGINES gating), the file it produces (for the output-size validity gate), and the work.
 *
 * Deliberately NOT a {@link com.journi.bench.variant.Variant} - that interface's contract
 * (process(sourceKey, destKey), workerCount(cfg), setUp/tearDown) is entirely about draining an
 * S3 keyspace across a thread pool. Implementing it for six fixed local commands would mean six
 * classes each ignoring both parameters.
 */
record LocalCase(String name, String engine, Path output, Action action) {

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }
}
