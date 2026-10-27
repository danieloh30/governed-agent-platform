package com.example.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ReconciliationService {
    public static final Set<String> SCENARIOS = Set.of("reconcile", "egress-check", "timeout", "script-error");
    @Inject SandboxGateway gateway;
    private final Semaphore capacity = new Semaphore(1);

    public record Evidence(String runId, String scenario, String status, String sandboxId,
            String executionId, Integer exitCode, String stdout, String stderr, String error,
            String cleanup, long durationMs, int commandTimeoutSeconds, Map<String, String> sha256,
            List<String> events) {}

    public Evidence run(String scenario) {
        if (!SCENARIOS.contains(scenario == null ? "" : scenario)) {
            throw new IllegalArgumentException("Choose reconcile, egress-check, timeout, or script-error");
        }
        if (!capacity.tryAcquire()) throw new IllegalStateException("A sandbox run is already active; wait for it to finish");
        long start = System.nanoTime();
        String runId = UUID.randomUUID().toString();
        String sandboxId = null, cleanup = "NOT_CREATED", status = "FAILED", error = null;
        int deadline = scenario.equals("timeout") ? 2 : 15;
        SandboxGateway.Outcome outcome = null;
        SandboxGateway.Session session = null;
        var events = new ArrayList<String>();
        var hashes = new LinkedHashMap<String, String>();
        try {
            Map<String, String> files = new LinkedHashMap<>();
            for (String name : List.of("ledger.csv", "settlements.csv", "reconcile.py")) {
                String content = resource(name);
                files.put(name, content);
                hashes.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8))));
            }
            events.add("Creating sandbox: 500m CPU, 256Mi memory, 180-second TTL, default-deny egress");
            cleanup = "CREATE_UNCONFIRMED";
            session = gateway.create(runId);
            sandboxId = session.id();
            cleanup = "PENDING";
            events.add("Created " + sandboxId);
            session.stage(files);
            events.add("Staged synthetic CSV fixtures and fixed script; SHA-256 recorded");
            outcome = session.execute(scenario, deadline);
            boolean success = outcome.error() == null && Integer.valueOf(0).equals(outcome.exitCode());
            status = success ? "COMPLETED" : "FAILED";
            error = outcome.error();
            if (!success && error == null) error = "Execution did not complete with exit code zero";
            events.add("Execution returned: " + status);
        } catch (Exception failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            events.add("Run failed before successful completion");
        } finally {
            if (session != null) {
                try {
                    session.kill();
                    cleanup = "DELETED";
                    events.add("Remote sandbox deletion acknowledged");
                } catch (Exception failure) {
                    cleanup = "DELETE_FAILED";
                    status = "FAILED";
                    error = append(error, "Remote deletion failed: " + failure.getMessage());
                    events.add("Deletion failed; operator must inspect sandbox " + sandboxId);
                } finally {
                    try { session.close(); }
                    catch (Exception failure) {
                        status = "FAILED";
                        error = append(error, "Client close failed: " + failure.getMessage());
                    }
                }
            }
            capacity.release();
        }
        return new Evidence(runId, scenario, status, sandboxId,
            outcome == null ? null : outcome.executionId(), outcome == null ? null : outcome.exitCode(),
            outcome == null ? "" : outcome.stdout(), outcome == null ? "" : outcome.stderr(), error,
            cleanup, (System.nanoTime() - start) / 1_000_000, deadline, hashes, events);
    }

    private static String append(String error, String detail) { return error == null ? detail : error + "; " + detail; }

    private static String resource(String name) throws IOException {
        try (var stream = ReconciliationService.class.getResourceAsStream("/workload/" + name)) {
            if (stream == null) throw new IOException("Missing packaged workload " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
