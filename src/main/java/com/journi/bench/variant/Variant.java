package com.journi.bench.variant;

import com.journi.bench.BenchConfig;
import com.journi.bench.harness.JobResult;

public interface Variant {
    String name();

    void setUp(BenchConfig cfg) throws Exception;

    /** Process exactly one S3 key. Must be safe to call concurrently from N worker threads. */
    JobResult process(String sourceKey, String destKey) throws Exception;

    void tearDown() throws Exception;

    /**
     * Deployment model sizing. Returns cfg.workers(), which itself defaults to
     * cfg.queueSize() (mirrors AppController.scala:71's imageThreadPool: one
     * worker per concurrent request slot, no backlog) - the already-proven-
     * optimal config from the earlier bash/hyperfine benchmarking, reused here
     * rather than re-swept. Set BENCH_WORKERS below BENCH_QUEUE_SIZE to instead
     * test a fixed-size worker pool draining a real backlog.
     */
    default int workerCount(BenchConfig cfg) {
        return cfg.workers();
    }
}
