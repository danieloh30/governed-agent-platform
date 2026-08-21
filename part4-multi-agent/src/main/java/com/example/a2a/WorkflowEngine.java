package com.example.a2a;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WorkflowEngine {

    @Inject
    GovernanceEngine governance;

    @Inject
    McpToolClient mcpClient;

    private final Map<String, TaskInstance> tasks = new ConcurrentHashMap<>();

    public TaskInstance submitTask(String taskId, String messageText) {
        TaskInstance task = tasks.computeIfAbsent(taskId, TaskInstance::new);

        if (task.getState() == TaskState.INPUT_REQUIRED) {
            task.addMessage("user", messageText);
            return task;
        }

        task.addMessage("user", messageText);
        task.incrementIteration();

        if (task.getIteration() > governance.getMaxIterations()) {
            task.setState(TaskState.FAILED);
            task.addMessage("agent", "Max iterations (" + governance.getMaxIterations() + ") exceeded. Task terminated by governance policy.");
            return task;
        }

        String operation = parseOperation(messageText);
        Map<String, String> args = parseArguments(messageText);
        task.setOperation(operation);
        task.setArguments(args);

        task.setState(TaskState.WORKING);
        task.addMessage("agent", "Validating operation '" + operation + "' against AGENTS.md governance rules...");

        GovernanceEngine.GovernanceResult gov = governance.validate(operation, args);
        task.setRiskLevel(gov.riskLevel());

        return switch (gov.decision()) {
            case BLOCKED -> {
                task.setState(TaskState.FAILED);
                task.addMessage("agent", gov.reason());
                yield task;
            }
            case REQUIRES_APPROVAL -> {
                task.setState(TaskState.INPUT_REQUIRED);
                task.setApprovalReason(gov.reason());
                task.addMessage("agent",
                    "HITL approval required. Reason: " + gov.reason() +
                    ". Workflow paused at state INPUT_REQUIRED. Awaiting human decision.");
                yield task;
            }
            case AUTO_APPROVED -> {
                task.addMessage("agent", "Governance check passed: " + gov.reason());
                String result = execute(operation, args);
                task.setState(TaskState.COMPLETED);
                task.addMessage("agent", result);
                task.addArtifact(operation + "-result", "Output from " + operation, result);
                yield task;
            }
        };
    }

    public TaskInstance getTask(String taskId) {
        TaskInstance task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Task not found: " + taskId);
        return task;
    }

    public TaskInstance cancelTask(String taskId) {
        TaskInstance task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Task not found: " + taskId);
        task.setState(TaskState.CANCELED);
        task.addMessage("agent", "Task canceled by client.");
        return task;
    }

    public TaskInstance approveTask(String taskId) {
        TaskInstance task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Task not found: " + taskId);
        if (task.getState() != TaskState.INPUT_REQUIRED) {
            throw new IllegalStateException("Task is not awaiting approval (current state: " + task.getState() + ")");
        }

        task.addMessage("user", "APPROVED by human operator");
        task.setState(TaskState.WORKING);
        task.addMessage("agent", "Human approval received. Resuming workflow execution...");

        String result = execute(task.getOperation(), task.getArguments());
        task.setState(TaskState.COMPLETED);
        task.addMessage("agent", result);
        task.addArtifact(task.getOperation() + "-result", "Output from " + task.getOperation(), result);
        return task;
    }

    public TaskInstance rejectTask(String taskId, String reason) {
        TaskInstance task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Task not found: " + taskId);
        if (task.getState() != TaskState.INPUT_REQUIRED) {
            throw new IllegalStateException("Task is not awaiting approval (current state: " + task.getState() + ")");
        }

        task.addMessage("user", "REJECTED by human operator: " + reason);
        task.setState(TaskState.FAILED);
        task.addMessage("agent", "Task rejected by human operator. Reason: " + reason);
        return task;
    }

    public List<Map<String, Object>> listTasks() {
        return tasks.values().stream().map(TaskInstance::toAdminView).toList();
    }

    private String execute(String operation, Map<String, String> args) {
        return switch (operation) {
            case "analyze-logs" -> {
                String zone = args.getOrDefault("service", "US-EAST-1").toUpperCase().replace("-", "_");
                if (!zone.contains("_")) zone = "US-EAST-1";
                String mcpResult = mcpClient.callTool("getZoneHealthLogs", Map.of("zoneId", zone));
                yield mcpResult != null
                    ? "[MCP → getZoneHealthLogs] " + mcpResult
                    : fallbackAnalyzeLogs(args);
            }

            case "health-check" -> {
                String service = args.getOrDefault("service", "api-gateway");
                String mcpResult = mcpClient.callTool("getSLACompliance", Map.of("serviceId", service));
                yield mcpResult != null
                    ? "[MCP → getSLACompliance] " + mcpResult
                    : fallbackHealthCheck(args);
            }

            case "generate-report" -> {
                String customer = args.getOrDefault("customer", "CUST-4091");
                String mcpResult = mcpClient.callTool("getAuditTrail", Map.of("customerId", customer));
                yield mcpResult != null
                    ? "[MCP → getAuditTrail] " + mcpResult
                    : fallbackGenerateReport(args);
            }

            case "process-refund" -> {
                String customerId = args.getOrDefault("customer", "CUST-4091");
                String mcpResult = mcpClient.callTool("getOrderStatus", Map.of("orderId", "ORD-" + customerId.replace("CUST-", "")));
                String prefix = mcpResult != null ? "[MCP → getOrderStatus] Verified: " + mcpResult + "\n" : "";
                yield prefix + fallbackProcessRefund(args);
            }

            case "migrate-schema" -> fallbackMigrateSchema(args);
            case "scale-infrastructure" -> fallbackScaleInfrastructure(args);
            case "deploy-production" -> fallbackDeployProduction(args);

            default -> "Operation '" + operation + "' completed successfully with args: " + args;
        };
    }

    private String fallbackAnalyzeLogs(Map<String, String> args) {
        return String.format(
            "Log analysis complete for service '%s' over %s. " +
            "Found 3 warnings, 1 error. " +
            "WARNING: High memory usage (87%%) in api-gateway. " +
            "WARNING: Slow query in 'users' table (avg 2.3s). " +
            "WARNING: TLS certificate expires in 14 days. " +
            "ERROR: Connection pool exhaustion in payment-service at 03:42 UTC.",
            args.getOrDefault("service", "all"), args.getOrDefault("timeframe", "24h"));
    }

    private String fallbackHealthCheck(Map<String, String> args) {
        return String.format(
            "Health check passed for '%s'. " +
            "CPU: 42%% | Memory: 31%% | Disk: 58%% | Network: 1.2Gbps. " +
            "All endpoints responding. Uptime: 14d 6h 32m. No incidents in 72 hours.",
            args.getOrDefault("service", "all-services"));
    }

    private String fallbackGenerateReport(Map<String, String> args) {
        return String.format(
            "Report generated: %s-report-%s.pdf. " +
            "Period: %s. 47 pages. " +
            "Key metrics: 99.97%% uptime, 45ms avg latency, 0 SLA violations. " +
            "Report available in artifact output.",
            args.getOrDefault("type", "summary"),
            UUID.randomUUID().toString().substring(0, 8),
            args.getOrDefault("period", "Q3-2024"));
    }

    private String fallbackProcessRefund(Map<String, String> args) {
        return String.format(
            "Refund processed successfully. " +
            "Customer: %s. Amount: $%s. " +
            "Transaction ID: TXN-%s. " +
            "Status: Credited to original payment method. " +
            "Settlement: 3-5 business days.",
            args.getOrDefault("customer", "unknown"),
            args.getOrDefault("amount", "0"),
            UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }

    private String fallbackMigrateSchema(Map<String, String> args) {
        return String.format(
            "Schema migration completed successfully. " +
            "Database: %s. Table: %s. " +
            "Changes: %s. " +
            "Rows affected: 0 (DDL operation). " +
            "Backup created: backup_%d.sql. " +
            "Migration ID: MIG-%s.",
            args.getOrDefault("database", "production"),
            args.getOrDefault("table", "unknown"),
            args.getOrDefault("changes", "column addition"),
            System.currentTimeMillis(),
            UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }

    private String fallbackScaleInfrastructure(Map<String, String> args) {
        return String.format(
            "Infrastructure scaling completed. " +
            "Service: %s. Target replicas: %s. " +
            "Current status: all replicas healthy. " +
            "Auto-scaling policy: CPU > 75%% threshold maintained.",
            args.getOrDefault("service", "unknown"),
            args.getOrDefault("replicas", "3"));
    }

    private String fallbackDeployProduction(Map<String, String> args) {
        return String.format(
            "Production deployment completed. " +
            "Service: %s. Version: %s. " +
            "Strategy: rolling update. Rollback window: 30 minutes. " +
            "Health checks: passing. Traffic: 100%% shifted.",
            args.getOrDefault("service", "unknown"),
            args.getOrDefault("version", "latest"));
    }

    private String parseOperation(String text) {
        return text.split("\\s+")[0].toLowerCase().trim();
    }

    private Map<String, String> parseArguments(String text) {
        Map<String, String> args = new LinkedHashMap<>();
        String[] tokens = text.split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].startsWith("--") && i + 1 < tokens.length) {
                args.put(tokens[i].substring(2), tokens[++i]);
            }
        }
        return args;
    }
}
