package com.example.sandbox;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationServiceTest {
    private static class FakeSession implements SandboxGateway.Session {
        boolean killed, closed;
        boolean failStage, failKill;
        String scenario;
        int timeout;
        Map<String, String> staged;
        public String id() { return "test-sandbox"; }
        public void stage(Map<String, String> files) {
            staged = files;
            if (failStage) throw new IllegalStateException("upload failed");
        }
        public SandboxGateway.Outcome execute(String scenario, int timeout) {
            this.scenario = scenario;
            this.timeout = timeout;
            return new SandboxGateway.Outcome("exec-1", scenario.equals("script-error") ? 7 : 0,
                "{}", "", scenario.equals("script-error") ? "Command failed" : null);
        }
        public void kill() {
            killed = true;
            if (failKill) throw new IllegalStateException("control plane unavailable");
        }
        public void close() { closed = true; }
    }

    private ReconciliationService service(SandboxGateway gateway) {
        var service = new ReconciliationService();
        service.gateway = gateway;
        return service;
    }

    @Test
    void recordsEvidenceAndDeletesSuccessfulSandbox() {
        var sandbox = new FakeSession();
        var result = service(id -> sandbox).run("reconcile");
        assertEquals("COMPLETED", result.status());
        assertEquals("DELETED", result.cleanup());
        assertEquals("test-sandbox", result.sandboxId());
        assertEquals(3, result.sha256().size());
        assertTrue(result.sha256().values().stream().allMatch(value -> value.length() == 64));
        assertTrue(sandbox.staged.get("ledger.csv").contains("PAY-1004,6000"));
        assertEquals(15, sandbox.timeout);
        assertTrue(sandbox.killed && sandbox.closed);
    }

    @Test
    void deletesAfterStagingAndExecutionFailures() {
        for (boolean staging : new boolean[] {true, false}) {
            var sandbox = new FakeSession();
            sandbox.failStage = staging;
            var result = service(id -> sandbox).run("script-error");
            assertEquals("FAILED", result.status());
            assertEquals("DELETED", result.cleanup());
            assertTrue(sandbox.killed && sandbox.closed);
            if (!staging) assertEquals(7, result.exitCode());
        }
    }

    @Test
    void cleanupFailureCannotBeReportedAsSuccess() {
        var sandbox = new FakeSession();
        sandbox.failKill = true;
        var result = service(id -> sandbox).run("reconcile");
        assertEquals("FAILED", result.status());
        assertEquals("DELETE_FAILED", result.cleanup());
        assertTrue(result.error().contains("Remote deletion failed"));
        assertTrue(sandbox.closed);
    }

    @Test
    void ambiguousCreationFailureIsNotClaimedAsCleanedUp() {
        var result = service(id -> { throw new IllegalStateException("create response lost"); }).run("reconcile");
        assertEquals("FAILED", result.status());
        assertEquals("CREATE_UNCONFIRMED", result.cleanup());
        assertNull(result.sandboxId());
    }

    @Test
    void timeoutExercisePassesShorterDeadline() {
        var sandbox = new FakeSession();
        service(id -> sandbox).run("timeout");
        assertEquals(2, sandbox.timeout);
        assertTrue(sandbox.killed && sandbox.closed);
    }

    @Test
    void arbitraryCommandsAreRejectedBeforeProvisioning() {
        var service = service(id -> { fail("must not create a sandbox"); return null; });
        for (String value : new String[] {"sh -c whoami", "../reconcile.py", "reconcile; id", ""}) {
            assertThrows(IllegalArgumentException.class, () -> service.run(value));
        }
        assertThrows(IllegalArgumentException.class, () -> service.run(null));
    }

    @Test
    void concurrentRequestsAreBoundedAndCapacityRecovers() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = service(id -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
            return new FakeSession();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.run("reconcile"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> service.run("reconcile"));
            } finally { release.countDown(); }
            assertEquals("COMPLETED", first.get(5, TimeUnit.SECONDS).status());
            assertEquals("COMPLETED", service.run("reconcile").status());
        }
    }
}
