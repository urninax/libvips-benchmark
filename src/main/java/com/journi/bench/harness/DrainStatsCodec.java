package com.journi.bench.harness;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Serializes a DrainStats to/from one line of text so a `run-variant` child process (see
 * Main.runVariant()) can hand its result back to the orchestrating parent process over stdout.
 * Needed because isolating each variant into its own JVM - so a variant's peak RSS reflects only
 * that variant's own work, not whatever a PREVIOUS variant left resident in a shared process (see
 * DrainHarness's baseline comment) - means results can no longer just be collected as in-process
 * objects; they have to cross a real process boundary as text.
 *
 * variantName and firstError are base64-encoded since they're free text that could otherwise
 * collide with the '|' delimiter; every other field is a plain number.
 */
public final class DrainStatsCodec {

    private static final String PREFIX = "##RESULT## ";

    private DrainStatsCodec() {}

    public static String encode(DrainStats s) {
        Stats.Summary d = s.drainSeconds();
        return PREFIX + String.join("|",
            b64(s.variantName()),
            String.valueOf(s.workers()),
            String.valueOf(s.queueSize()),
            String.valueOf(d.mean()), String.valueOf(d.stddev()), String.valueOf(d.min()), String.valueOf(d.max()), String.valueOf(d.n()),
            String.valueOf(s.throughputPerSec()),
            String.valueOf(s.peakSelfRssKb()), String.valueOf(s.peakChildrenRssKb()), String.valueOf(s.peakTotalRssKb()),
            String.valueOf(s.vmHwmKb()), String.valueOf(s.vipsTrackedPeakKb()),
            String.valueOf(s.p50Ms()), String.valueOf(s.p95Ms()), String.valueOf(s.p99Ms()), String.valueOf(s.maxMs()),
            String.valueOf(s.latencySampleCount()),
            String.valueOf(s.avgOverlapRatio()),
            String.valueOf(s.cpuUtilPercent()),
            String.valueOf(s.gcCount()), String.valueOf(s.gcPauseMs()),
            String.valueOf(s.failureCount()),
            b64(s.firstError() == null ? "" : s.firstError())
        );
    }

    /** Returns null for any line that isn't a result line - callers should echo those straight to stdout. */
    public static DrainStats decode(String line) {
        if (!line.startsWith(PREFIX)) return null;
        String[] f = line.substring(PREFIX.length()).split("\\|", -1);
        int i = 0;
        String variantName = unb64(f[i++]);
        int workers = Integer.parseInt(f[i++]);
        int queueSize = Integer.parseInt(f[i++]);
        Stats.Summary drainSeconds = new Stats.Summary(
            Double.parseDouble(f[i++]), Double.parseDouble(f[i++]), Double.parseDouble(f[i++]),
            Double.parseDouble(f[i++]), Integer.parseInt(f[i++]));
        double throughput = Double.parseDouble(f[i++]);
        long peakSelfRssKb = Long.parseLong(f[i++]);
        long peakChildrenRssKb = Long.parseLong(f[i++]);
        long peakTotalRssKb = Long.parseLong(f[i++]);
        long vmHwmKb = Long.parseLong(f[i++]);
        long vipsTrackedPeakKb = Long.parseLong(f[i++]);
        double p50 = Double.parseDouble(f[i++]);
        double p95 = Double.parseDouble(f[i++]);
        double p99 = Double.parseDouble(f[i++]);
        double max = Double.parseDouble(f[i++]);
        int latencySampleCount = Integer.parseInt(f[i++]);
        double overlap = Double.parseDouble(f[i++]);
        double cpuUtil = Double.parseDouble(f[i++]);
        long gcCount = Long.parseLong(f[i++]);
        long gcPauseMs = Long.parseLong(f[i++]);
        int failureCount = Integer.parseInt(f[i++]);
        String firstError = unb64(f[i++]);
        if (firstError.isEmpty()) firstError = null;
        return new DrainStats(variantName, workers, queueSize, drainSeconds, throughput,
            peakSelfRssKb, peakChildrenRssKb, peakTotalRssKb, vmHwmKb, vipsTrackedPeakKb,
            p50, p95, p99, max, latencySampleCount, overlap, cpuUtil, gcCount, gcPauseMs, failureCount, firstError);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }
}
