package com.example.sandbox;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OpenSandboxGateway implements SandboxGateway {
    @ConfigProperty(name = "sandbox.domain") String domain;
    @ConfigProperty(name = "sandbox.api-key") String apiKey;
    @ConfigProperty(name = "sandbox.image") String image;

    @Override
    public Session create(String runId) {
        var connection = ConnectionConfig.builder().domain(domain).protocol("http")
            .apiKey(apiKey).useServerProxy(true).requestTimeout(Duration.ofSeconds(60)).build();
        Sandbox sandbox = Sandbox.builder().connectionConfig(connection).image(image)
            .timeout(Duration.ofMinutes(3)).readyTimeout(Duration.ofSeconds(60))
            .resource(Map.of("cpu", "500m", "memory", "256Mi"))
            .metadata("tutorial", "governed-agent-platform-part7").metadata("run-id", runId)
            .networkPolicy(NetworkPolicy.builder().defaultAction(NetworkPolicy.DefaultAction.DENY).build())
            .build();
        return new Session() {
            public String id() { return sandbox.getId(); }

            public void stage(Map<String, String> files) {
                sandbox.files().write(files.entrySet().stream().map(entry -> WriteEntry.builder()
                    .path("/tmp/" + entry.getKey()).data(entry.getValue()).mode(644).build()).toList());
            }

            public Outcome execute(String scenario, int timeoutSeconds) {
                if (!ReconciliationService.SCENARIOS.contains(scenario)) throw new IllegalArgumentException("Unknown scenario");
                var execution = sandbox.commands().run(RunCommandRequest.builder()
                    // execd v1.1.0 uses command text; every token is fixed or allowlisted.
                    .command("python3 -I /tmp/reconcile.py " + scenario)
                    .timeout(Duration.ofSeconds(timeoutSeconds)).build());
                return new Outcome(execution.getId(), execution.getExitCode(),
                    execution.getLogs().getStdout().stream().map(line -> line.getText()).collect(Collectors.joining("\n")),
                    execution.getLogs().getStderr().stream().map(line -> line.getText()).collect(Collectors.joining("\n")),
                    execution.getError() == null ? null : execution.getError().getName() + ": " + execution.getError().getValue());
            }

            public void kill() { sandbox.kill(); }
            public void close() { sandbox.close(); }
        };
    }
}
