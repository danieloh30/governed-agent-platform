package com.example.sandbox;

import java.util.Map;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ReconciliationEndpoint {
    @Inject ReconciliationService service;

    public record RunRequest(String scenario) {}

    @GET
    @Path("policy")
    public Map<String, Object> policy() {
        return Map.of("scenarios", ReconciliationService.SCENARIOS, "cpu", "500m", "memory", "256Mi",
            "ttlSeconds", 180, "networkDefault", "DENY", "maxConcurrentRuns", 1,
            "commandTimeoutSeconds", 15, "timeoutDemoSeconds", 2, "syntheticData", true);
    }

    @POST
    @Path("runs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    public Response run(RunRequest request) {
        try {
            return Response.ok(service.run(request == null ? null : request.scenario())).build();
        } catch (IllegalArgumentException invalid) {
            return Response.status(400).entity(Map.of("error", invalid.getMessage())).build();
        } catch (IllegalStateException busy) {
            return Response.status(409).entity(Map.of("error", busy.getMessage())).build();
        }
    }

    @Tool(description = "Run a fixed synthetic settlement reconciliation or a sandbox control check. "
        + "Creates a temporary OpenSandbox with resource limits and denied egress, then deletes it. "
        + "No arbitrary commands, paths, or payment actions are accepted. Inspect status and cleanup in the result.")
    @Blocking
    public ReconciliationService.Evidence reconcileSettlements(
            @ToolArg(description = "One of reconcile, egress-check, timeout, script-error")
            @NotNull @Pattern(regexp = "reconcile|egress-check|timeout|script-error") String scenario) {
        return service.run(scenario);
    }
}
