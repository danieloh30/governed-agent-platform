package com.example.a2a;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class GovernanceEngine {

    public enum Decision { AUTO_APPROVED, REQUIRES_APPROVAL, BLOCKED }

    public record GovernanceResult(Decision decision, String reason, String riskLevel) {}

    private final Map<String, String> autoApproved = new LinkedHashMap<>();
    private final Map<String, String> requiresApproval = new LinkedHashMap<>();
    private final Map<String, String> blocked = new LinkedHashMap<>();
    private int maxIterations = 10;

    void onStart(@Observes StartupEvent ev) {
        parseAgentsMd();
    }

    private void parseAgentsMd() {
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("AGENTS.md")) {
            if (is == null) return;
            List<String> lines = new BufferedReader(new InputStreamReader(is)).lines().toList();
            String section = null;

            for (String line : lines) {
                if (line.startsWith("### Auto-Approved")) {
                    section = "auto";
                } else if (line.startsWith("### Requires Human Approval")) {
                    section = "hitl";
                } else if (line.startsWith("### Blocked")) {
                    section = "blocked";
                } else if (line.startsWith("## ") || line.startsWith("# ")) {
                    section = null;
                } else if (line.startsWith("- ") && section != null) {
                    String entry = line.substring(2).trim();
                    String[] parts = entry.split(":", 2);
                    String op = parts[0].trim();
                    String desc = parts.length > 1 ? parts[1].trim() : "";
                    switch (section) {
                        case "auto" -> autoApproved.put(op, desc);
                        case "hitl" -> requiresApproval.put(op, desc);
                        case "blocked" -> blocked.put(op, desc);
                    }
                }

                if (line.startsWith("max-iterations:")) {
                    maxIterations = Integer.parseInt(line.split(":", 2)[1].trim());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse AGENTS.md", e);
        }
    }

    public GovernanceResult validate(String operation, Map<String, String> args) {
        if (blocked.containsKey(operation)) {
            return new GovernanceResult(Decision.BLOCKED,
                "Operation '" + operation + "' is blocked by governance policy: " + blocked.get(operation),
                "critical");
        }

        if (requiresApproval.containsKey(operation)) {
            String reason = requiresApproval.get(operation);
            if (operation.equals("process-refund")) {
                int amount = 0;
                try { amount = Integer.parseInt(args.getOrDefault("amount", "0")); } catch (NumberFormatException ignored) {}
                if (amount <= 1000) {
                    return new GovernanceResult(Decision.AUTO_APPROVED,
                        "Refund amount $" + amount + " within auto-approval threshold ($1000)",
                        "low");
                }
                reason = "Refund amount $" + amount + " exceeds auto-approval threshold ($1000)";
            }
            return new GovernanceResult(Decision.REQUIRES_APPROVAL, reason, "high");
        }

        if (autoApproved.containsKey(operation)) {
            return new GovernanceResult(Decision.AUTO_APPROVED,
                "Operation '" + operation + "' is auto-approved by governance policy",
                "low");
        }

        return new GovernanceResult(Decision.REQUIRES_APPROVAL,
            "Unknown operation '" + operation + "' requires manual approval",
            "medium");
    }

    public int getMaxIterations() { return maxIterations; }

    public Map<String, Object> getRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("maxIterations", maxIterations);
        rules.put("autoApproved", autoApproved);
        rules.put("requiresApproval", requiresApproval);
        rules.put("blocked", blocked);
        return rules;
    }

    public Set<String> getAllOperations() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(autoApproved.keySet());
        all.addAll(requiresApproval.keySet());
        all.addAll(blocked.keySet());
        return all;
    }
}
