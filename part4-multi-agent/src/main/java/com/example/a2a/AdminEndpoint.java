package com.example.a2a;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.time.OffsetDateTime;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;

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

    @Inject
    TaskStore taskStore;

    @GET
    @Path("tasks")
    public List<Map<String, Object>> listTasks() {
        return engine.listTasks();
    }

    @POST
    @Path("tasks/{id}/approve")
    public Object approveTask(@PathParam("id") String taskId) {
        TaskInstance task = engine.approveTask(taskId);
        syncA2aTask(task);
        return task.toA2AResponse();
    }

    @POST
    @Path("tasks/{id}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Object rejectTask(@PathParam("id") String taskId, Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "Rejected by operator") : "Rejected by operator";
        TaskInstance task = engine.rejectTask(taskId, reason);
        syncA2aTask(task);
        return task.toA2AResponse();
    }

    private void syncA2aTask(TaskInstance workflowTask) {
        Task stored = taskStore.get(workflowTask.getId());
        if (stored == null) return;
        var state = workflowTask.getState() == TaskState.COMPLETED
            ? org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED
            : org.a2aproject.sdk.spec.TaskState.TASK_STATE_FAILED;
        Message outcome = Message.builder().messageId(UUID.randomUUID().toString())
            .role(Message.Role.ROLE_AGENT).taskId(stored.id()).contextId(stored.contextId())
            .parts(new TextPart(workflowTask.getLastAgentMessage())).build();
        var history = stored.history() == null ? new ArrayList<Message>() : new ArrayList<>(stored.history());
        history.add(outcome);
        var artifacts = stored.artifacts() == null ? new ArrayList<Artifact>() : new ArrayList<>(stored.artifacts());
        if (workflowTask.getState() == TaskState.COMPLETED) {
            artifacts.add(Artifact.builder().artifactId(UUID.randomUUID().toString())
                .name(workflowTask.getOperation() + "-result")
                .parts(new TextPart(workflowTask.getLastAgentMessage())).build());
        }
        taskStore.save(Task.builder(stored)
            .status(new TaskStatus(state, outcome, OffsetDateTime.now()))
            .history(history).artifacts(artifacts).build(), false);
    }

    @GET
    @Path("governance")
    public Map<String, Object> getGovernance() {
        return governance.getRules();
    }

    @GET
    @Path("governance/agents.md")
    @Produces(MediaType.TEXT_PLAIN)
    public String getGovernanceSource() {
        return governance.getPolicySource();
    }

    // Invalid task IDs or bad state transitions surface as 400s — one mapper
    // replaces the per-endpoint try/catch blocks.
    @ServerExceptionMapper({IllegalArgumentException.class, IllegalStateException.class})
    public RestResponse<Map<String, String>> mapBadRequest(RuntimeException e) {
        return RestResponse.status(RestResponse.Status.BAD_REQUEST, Map.of("error", e.getMessage()));
    }
}
