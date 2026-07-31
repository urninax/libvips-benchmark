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
     * Deployment model sizing. GM/IM: cores*2 single-threaded workers (mirrors
     * AppController.scala:71's imageThreadPool). vips: cores*2 workers too, each
     * with VIPS_CONCURRENCY=1 - the already-proven-optimal config from the
     * earlier bash/hyperfine benchmarking, reused here rather than re-swept.
     */
    default int workerCount(BenchConfig cfg) {
        return cfg.queueSize();
    }
}
