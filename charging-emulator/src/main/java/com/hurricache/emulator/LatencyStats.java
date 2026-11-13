package com.hurricache.emulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Charging latency collection. Each worker writes to its own uncontended buffer;
 * at the end the buffers are merged and percentiles are computed.
 */
public final class LatencyStats {

    /** Per-worker buffer: latencies in microseconds, no synchronization. */
    public static final class Recorder {
        private final List<Long> latenciesUs = new ArrayList<>();
        private long chargeOk = 0;
        private long chargeInsufficient = 0;
        private long rollbacks = 0;
        private long errors = 0;

        public void record(long latencyNanos, boolean rollback, boolean success) {
            latenciesUs.add(latencyNanos / 1000);
            if (rollback) {
                rollbacks++;
            } else if (success) {
                chargeOk++;
            } else {
                chargeInsufficient++;
            }
        }

        public void error() {
            errors++;
        }
    }

    private final List<Recorder> recorders = new ArrayList<>();

    public synchronized Recorder newRecorder() {
        Recorder r = new Recorder();
        recorders.add(r);
        return r;
    }

    public synchronized void report(String label, long wallNanos) {
        List<Long> all = new ArrayList<>();
        long chargeOk = 0, chargeInsufficient = 0, rollbacks = 0, errors = 0;
        for (Recorder r : recorders) {
            all.addAll(r.latenciesUs);
            chargeOk += r.chargeOk;
            chargeInsufficient += r.chargeInsufficient;
            rollbacks += r.rollbacks;
            errors += r.errors;
        }
        long total = chargeOk + chargeInsufficient + rollbacks;
        System.out.println("=== " + label + " ===");
        System.out.printf("events: total=%d (charge ok=%d, insufficient=%d, rollback=%d), errors=%d%n",
                total, chargeOk, chargeInsufficient, rollbacks, errors);
        System.out.printf("throughput: %.1f ops/sec%n", total * 1e9 / Math.max(1, wallNanos));
        if (all.isEmpty()) {
            System.out.println("no latency samples");
            return;
        }
        long[] sorted = all.stream().mapToLong(Long::longValue).sorted().toArray();
        double avg = Arrays.stream(sorted).average().orElse(0);
        System.out.printf("latency us: avg=%.1f p50=%d p95=%d p99=%d max=%d%n",
                avg, pct(sorted, 0.50), pct(sorted, 0.95), pct(sorted, 0.99), sorted[sorted.length - 1]);
    }

    private static long pct(long[] sorted, double q) {
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * q))];
    }
}
