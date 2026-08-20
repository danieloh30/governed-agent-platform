package com.example.a2a;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskInstance {

    private final String id;
    private TaskState state = TaskState.SUBMITTED;
    private String operation;
    private Map<String, String> arguments = new LinkedHashMap<>();
    private final List<Map<String, Object>> history = new ArrayList<>();
    private final List<Map<String, Object>> artifacts = new ArrayList<>();
    private String approvalReason;
    private String riskLevel = "low";
    private int iteration = 0;
    private final long createdAt = System.currentTimeMillis();

    public TaskInstance(String id) {
        this.id = id;
    }

    public String getId() { return id; }
    public TaskState getState() { return state; }
    public void setState(TaskState state) { this.state = state; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public Map<String, String> getArguments() { return arguments; }
    public void setArguments(Map<String, String> arguments) { this.arguments = arguments; }
    public String getApprovalReason() { return approvalReason; }
    public void setApprovalReason(String reason) { this.approvalReason = reason; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public int getIteration() { return iteration; }
    public void incrementIteration() { this.iteration++; }

    public void addMessage(String role, String text) {
        history.add(Map.of(
            "role", role,
            "parts", List.of(Map.of("type", "text", "text", text))
        ));
    }

    public void addArtifact(String name, String description, String content) {
        artifacts.add(Map.of(
            "name", name,
            "description", description,
            "parts", List.of(Map.of("type", "text", "text", content))
        ));
    }

    @SuppressWarnings("unchecked")
    public String getLastAgentMessage() {
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = history.get(i);
            if ("agent".equals(msg.get("role"))) {
                var parts = (List<Map<String, Object>>) msg.get("parts");
                if (parts != null && !parts.isEmpty()) {
                    return (String) parts.getFirst().get("text");
                }
            }
        }
        return "";
    }

    public Map<String, Object> toA2AResponse() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", state.name().toLowerCase().replace('_', '-'));

        if (!history.isEmpty()) {
            status.put("message", history.getLast());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("status", status);
        result.put("history", history);
        result.put("artifacts", artifacts);
        return result;
    }

    public Map<String, Object> toAdminView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("state", state.name().toLowerCase().replace('_', '-'));
        view.put("operation", operation);
        view.put("arguments", arguments);
        view.put("riskLevel", riskLevel);
        view.put("approvalReason", approvalReason);
        view.put("iteration", iteration);
        view.put("createdAt", createdAt);
        view.put("history", history);
        view.put("artifacts", artifacts);
        return view;
    }
}
