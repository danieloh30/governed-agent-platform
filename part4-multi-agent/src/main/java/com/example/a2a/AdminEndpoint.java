package com.example.a2a;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
    public Response approveTask(@PathParam("id") String taskId) {
        try {
            Map<String, Object> result = engine.approveTask(taskId);
            return Response.ok(result).build();
        } catch (Exception e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("tasks/{id}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response rejectTask(@PathParam("id") String taskId, Map<String, String> body) {
        try {
            String reason = body != null ? body.getOrDefault("reason", "Rejected by operator") : "Rejected by operator";
            Map<String, Object> result = engine.rejectTask(taskId, reason);
            return Response.ok(result).build();
        } catch (Exception e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("governance")
    public Map<String, Object> getGovernance() {
        return governance.getRules();
    }
}
