package com.journi.bench;

import java.util.Map;

/** Env-var-driven configuration. Every knob has a sane default so `docker run bench run` just works. */
public record BenchConfig(
    String s3Endpoint,
    boolean s3PathStyle,
    String s3Region,
    String s3Bucket,
    int s3MaxConnections,
    int s3TimeoutSeconds,

    int cores,
    int queueSize,          // defaults to cores*2, mirrors AppController.scala:71
    int workers,            // defaults to queueSize (1 job/worker, no backlog); set BENCH_WORKERS
                            // lower than BENCH_QUEUE_SIZE to test a fixed worker pool draining a
                            // real backlog instead
    int warmupReps,
    int measuredReps,

    String vipsConcurrency, // forwarded as VIPS_CONCURRENCY env for subprocess-based checks; vips-ffm reads env itself
    long partSizeBytes,     // S3 multipart threshold, default 5 MiB

    String[] engines,       // gm, im, vips
    String[] resizeTypes,   // standard, bleed - e.g. BENCH_RESIZE_TYPES=standard to skip IM's bleed
                            // resize entirely, which is dramatically slower per-job (see
                            // ImBleedVariant) and not always what you want to pay for in a quick run
    String[] ioModes,       // buffered, streamed (vips only)
    String[] keys,          // S3 key BASE NAMES to seed/process, no extension -
                            // inputFormat.ext is appended (see Main.seed()/run())

    ImageFormat inputFormat,  // BENCH_INPUT_FORMAT: jpg, png, or webp - which fixture gets fetched
    ImageFormat outputFormat, // BENCH_OUTPUT_FORMAT: jpg, png, or webp - which format gets saved/uploaded

    String resultsDir,
    Boolean readCallbackDebug,
    Boolean progressEnabled,
    String runId
) {
    public static BenchConfig fromEnv() {
        Map<String, String> env = System.getenv();
        int cores = Runtime.getRuntime().availableProcessors();
        int queueSize = Integer.parseInt(str(env, "BENCH_QUEUE_SIZE", String.valueOf(cores * 2)));
        return new BenchConfig(
            str(env, "S3_ENDPOINT", "http://localhost:9000"),
            Boolean.parseBoolean(str(env, "S3_PATH_STYLE", "true")),
            str(env, "AWS_REGION", "eu-central-1"),
            str(env, "S3_BUCKET", "journi-bench"),
            Integer.parseInt(str(env, "S3_MAX_CONNECTIONS", "64")),
            Integer.parseInt(str(env, "S3_TIMEOUT_SECONDS", "30")),

            cores,
            queueSize,
            Integer.parseInt(str(env, "BENCH_WORKERS", String.valueOf(queueSize))),
            Integer.parseInt(str(env, "BENCH_WARMUP_RUNS", "2")),
            Integer.parseInt(str(env, "BENCH_MIN_RUNS", "5")),

            str(env, "VIPS_CONCURRENCY", "1"),
            Long.parseLong(str(env, "PART_SIZE_BYTES", String.valueOf(5L * 1024 * 1024))),

            str(env, "BENCH_ENGINES", "gm,im,vips").split(","),
            str(env, "BENCH_RESIZE_TYPES", "standard,bleed").split(","),
            str(env, "BENCH_IO_MODES", "buffered,streamed").split(","),
            str(env, "BENCH_KEYS", "fixtures/baseline,fixtures/progressive").split(","),

            ImageFormat.fromEnv(str(env, "BENCH_INPUT_FORMAT", "jpg")),
            ImageFormat.fromEnv(str(env, "BENCH_OUTPUT_FORMAT", "jpg")),

            str(env, "RESULTS_DIR", "/results"),
            Boolean.parseBoolean(str(env, "READ_CALLBACK_DEBUG", "false")),
            Boolean.parseBoolean(str(env, "PROGRESS_ENABLED", "true")),
            str(env, "RUN_ID", "default")
        );
    }

    /**
     * Like Map.getOrDefault, but also falls back to the default when the env
     * var is SET but blank - docker-compose's `${VAR:-}` interpolation sets
     * an empty string rather than leaving the var unset, which would
     * otherwise reach Integer.parseInt("") and blow up.
     */
    private static String str(Map<String, String> env, String key, String fallback) {
        String v = env.get(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
