package com.example.sandbox;

import java.util.Map;

/** Boundary between the workflow and the real OpenSandbox SDK. */
public interface SandboxGateway {
    Session create(String runId);

    interface Session extends AutoCloseable {
        String id();
        void stage(Map<String, String> files);
        Outcome execute(String scenario, int timeoutSeconds);
        void kill();
        @Override void close();
    }

    record Outcome(String executionId, Integer exitCode, String stdout, String stderr, String error) {}
}
