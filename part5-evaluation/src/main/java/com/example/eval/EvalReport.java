package com.example.eval;

import java.time.Instant;
import java.util.List;

public record EvalReport(
        String suiteName,
        String timestamp,
        int total,
        int passed,
        int failed,
        double accuracy,
        LatencyStats latency,
        List<EvalResult> results) {

    public record LatencyStats(long p50, long p95, long p99, long max) {
    }

    public static EvalReport from(String suiteName, List<EvalResult> results) {
        int passed = (int) results.stream().filter(EvalResult::passed).count();
        int total = results.size();
        double accuracy = total > 0 ? (double) passed / total * 100.0 : 0.0;

        List<Long> latencies = results.stream()
                .map(EvalResult::latencyMs)
                .sorted()
                .toList();

        LatencyStats stats = latencies.isEmpty()
                ? new LatencyStats(0, 0, 0, 0)
                : new LatencyStats(
                        percentile(latencies, 50),
                        percentile(latencies, 95),
                        percentile(latencies, 99),
                        latencies.getLast());

        return new EvalReport(suiteName, Instant.now().toString(),
                total, passed, total - passed, accuracy, stats, results);
    }

    private static long percentile(List<Long> sorted, int p) {
        int idx = Math.min((int) Math.ceil(p / 100.0 * sorted.size()) - 1, sorted.size() - 1);
        return sorted.get(Math.max(0, idx));
    }
}
