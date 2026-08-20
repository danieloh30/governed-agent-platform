package com.example.a2a;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class A2AEndpoint {

    @Inject
    WorkflowEngine engine;

    @Inject
    GovernanceEngine governance;

    @GET
    @Path(".well-known/agent-card.json")
    public Map<String, Object> agentCard() {
        return Map.of(
            "name", "enterprise-workflow-agent",
            "description", "Multi-step enterprise workflow orchestration with HITL approval gates, " +
                           "governed by AGENTS.md rules. Handles database migrations, refund processing, " +
                           "infrastructure scaling, and production deployments.",
            "url", "http://localhost:8082/a2a",
            "version", "1.0.0",
            "capabilities", Map.of(
                "streaming", false,
                "pushNotifications", false
            ),
            "skills", List.of(
                Map.of("id", "analyze-logs", "name", "Log Analysis",
                       "description", "Analyze application logs for warnings and errors"),
                Map.of("id", "health-check", "name", "Health Check",
                       "description", "Verify service health and resource utilization"),
                Map.of("id", "generate-report", "name", "Report Generation",
                       "description", "Generate compliance and metrics reports"),
                Map.of("id", "migrate-schema", "name", "Schema Migration",
                       "description", "Execute database schema migrations (requires HITL approval)"),
                Map.of("id", "process-refund", "name", "Refund Processing",
                       "description", "Process customer refunds (HITL required above $1000)"),
                Map.of("id", "scale-infrastructure", "name", "Infrastructure Scaling",
                       "description", "Scale service replicas (requires HITL approval)"),
                Map.of("id", "deploy-production", "name", "Production Deployment",
                       "description", "Deploy to production environment (requires HITL approval)")
            ),
            "governancePolicy", "AGENTS.md"
        );
    }

    @POST
    @Path("a2a")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response handleRpc(JsonNode request) {
        String method = request.path("method").asText("");
        JsonNode params = request.path("params");
        JsonNode id = request.path("id");

        try {
            Object result = switch (method) {
                case "tasks/send" -> {
                    String taskId = params.path("id").asText();
                    String text = params.path("message").path("parts").get(0).path("text").asText();
                    yield engine.submitTask(taskId, text);
                }
                case "tasks/get" -> engine.getTask(params.path("id").asText());
                case "tasks/cancel" -> engine.cancelTask(params.path("id").asText());
                default -> throw new IllegalArgumentException("Unknown method: " + method);
            };

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id.isNumber() ? id.intValue() : id.asText());
            response.put("result", result);
            return Response.ok(response).build();

        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("jsonrpc", "2.0");
            error.put("id", id.isNumber() ? id.intValue() : id.asText());
            error.put("error", Map.of("code", -32603, "message", e.getMessage()));
            return Response.ok(error).build();
        }
    }
}
