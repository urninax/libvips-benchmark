package com.journi.bench.variant;

import com.journi.bench.metrics.RssSampler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wraps ProcessBuilder for gm/convert subprocess invocation.
 *
 * Built as argv Lists, never a single shell string - the real repo's Scala
 * GraphicsMagickClient.buildCmd returns a String fed through scala.sys.process,
 * which does naive whitespace tokenization; that's a latent quoting bug not
 * worth replicating here.
 *
 * OMP_NUM_THREADS=1 / MAGICK_THREAD_LIMIT=1 pin each subprocess to single-
 * threaded, matching the production model's assumption that imageThreadPool's
 * cores*2 concurrent workers are each single-threaded. If IM silently used all
 * cores per subprocess (it can, built with OpenMP unlike GM), the concurrency
 * model would be oversubscribed by ~8x and invalidate the whole comparison -
 * verified via `convert -list resource` during the build-order spike.
 */
public final class ProcessRunner {

    private ProcessRunner() {}

    public static void run(List<String> argv, RssSampler rssSampler, long timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("OMP_NUM_THREADS", "1");
        pb.environment().put("MAGICK_THREAD_LIMIT", "1");
        // Discard stdout/stderr rather than let a chatty convert fill the 64KB
        // pipe buffer and deadlock under cores*2 concurrent subprocesses.
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process p = pb.start();
        long pid = p.pid();
        if (rssSampler != null) rssSampler.registerChild(pid);
        try {
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("Process timed out after " + timeoutSeconds + "s: " + argv);
            }
            int exit = p.exitValue();
            if (exit != 0) {
                throw new IOException("Process exited " + exit + ": " + String.join(" ", argv));
            }
        } finally {
            if (rssSampler != null) rssSampler.unregisterChild(pid);
        }
    }

    static Path tempFile(Path dir, String prefix, String suffix) throws IOException {
        return java.nio.file.Files.createTempFile(dir, prefix, suffix);
    }
}
