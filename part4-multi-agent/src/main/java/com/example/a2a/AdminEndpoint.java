package com.example.a2a;

import java.util.List;
import java.util.Map;

import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class AdminEndpoint {

    @Inject
    WorkflowEngine engine;

    @Inject
    GovernanceEngine governance;

    @GET
    @Path("tasks")
    public List<Map<String, Object>> listTasks() {
        return engine.listTasks();
    }

    @POST
    @Path("tasks/{id}/approve")
    public Object approveTask(@PathParam("id") String taskId) {
        return engine.approveTask(taskId).toA2AResponse();
    }

    @POST
    @Path("tasks/{id}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Object rejectTask(@PathParam("id") String taskId, Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "Rejected by operator") : "Rejected by operator";
        return engine.rejectTask(taskId, reason).toA2AResponse();
    }

    @GET
    @Path("governance")
    public Map<String, Object> getGovernance() {
        return governance.getRules();
    }

    // Invalid task IDs or bad state transitions surface as 400s — one mapper
    // replaces the per-endpoint try/catch blocks.
    @ServerExceptionMapper({IllegalArgumentException.class, IllegalStateException.class})
    public RestResponse<Map<String, String>> mapBadRequest(RuntimeException e) {
        return RestResponse.status(RestResponse.Status.BAD_REQUEST, Map.of("error", e.getMessage()));
    }
}
