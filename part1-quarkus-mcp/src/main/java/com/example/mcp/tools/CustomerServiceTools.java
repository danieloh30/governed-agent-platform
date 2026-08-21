package com.example.mcp.tools;

import java.util.List;

import com.example.mcp.model.AuditEvent;
import com.example.mcp.model.CustomerStatusResponse;
import com.example.mcp.model.OrderStatusResponse;
import com.example.mcp.model.SLAComplianceResponse;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@ApplicationScoped
public class CustomerServiceTools {

    @Tool(description = "Retrieve the current account status, service tier, and primary deployment region for a given customer.")
    public CustomerStatusResponse getCustomerStatus(
            @ToolArg(description = "Customer ID formatted as CUST-XXXX")
            @NotNull
            @Pattern(regexp = "^CUST-[0-9]{4,8}$")
            String customerId) {

        return switch (customerId) {
            case "CUST-4091" -> new CustomerStatusResponse("CUST-4091", "ACTIVE", "ENTERPRISE_TIER", "US-EAST-1");
            case "CUST-2187" -> new CustomerStatusResponse("CUST-2187", "ACTIVE", "BUSINESS_TIER", "EU-WEST-1");
            case "CUST-7734" -> new CustomerStatusResponse("CUST-7734", "SUSPENDED", "STARTER_TIER", "AP-SOUTH-1");
            default -> new CustomerStatusResponse(customerId, "NOT_FOUND", "UNKNOWN", "UNKNOWN");
        };
    }

    @Tool(description = "Retrieve recent health-check logs and diagnostic metrics for a specified availability zone.")
    public List<String> getZoneHealthLogs(
            @ToolArg(description = "Zone identifier, e.g., US-EAST-1")
            @Size(max = 20)
            String zoneId) {

        return List.of(
                "[" + zoneId + "] CPU utilization: 42% (healthy)",
                "[" + zoneId + "] Memory pressure: 31% (normal)",
                "[" + zoneId + "] Network I/O: 1.2 Gbps ingress / 0.8 Gbps egress",
                "[" + zoneId + "] Disk IOPS: 12,400 read / 8,300 write (within SLA)",
                "[" + zoneId + "] Active connections: 18,230 (capacity: 50,000)",
                "[" + zoneId + "] Last incident: none in past 72 hours"
        );
    }

    @Tool(description = "Track the current status, item count, and estimated delivery for an enterprise order.")
    public OrderStatusResponse getOrderStatus(
            @ToolArg(description = "Order ID formatted as ORD-XXXXXXXX")
            @NotNull
            @Pattern(regexp = "^ORD-[0-9]{8}$")
            String orderId) {

        return switch (orderId) {
            case "ORD-20240815" -> new OrderStatusResponse("ORD-20240815", "SHIPPED", 12, "$48,750.00", "2024-08-22", "US-EAST-1");
            case "ORD-20240901" -> new OrderStatusResponse("ORD-20240901", "PROCESSING", 5, "$12,300.00", "2024-09-10", "EU-WEST-1");
            case "ORD-20241003" -> new OrderStatusResponse("ORD-20241003", "DELIVERED", 28, "$134,500.00", "2024-10-08", "AP-SOUTH-1");
            default -> new OrderStatusResponse(orderId, "NOT_FOUND", 0, "$0.00", "N/A", "UNKNOWN");
        };
    }

    @Tool(description = "Retrieve SLA compliance metrics including uptime, latency, and violation count for a service.")
    public SLAComplianceResponse getSLACompliance(
            @ToolArg(description = "Service identifier, e.g., api-gateway, auth-service")
            @NotNull
            @Size(max = 40)
            String serviceId) {

        return switch (serviceId) {
            case "api-gateway" -> new SLAComplianceResponse("api-gateway", 99.97, "45ms", 99.99, 0, "2024-Q3");
            case "auth-service" -> new SLAComplianceResponse("auth-service", 99.82, "120ms", 99.95, 3, "2024-Q3");
            case "data-pipeline" -> new SLAComplianceResponse("data-pipeline", 98.50, "340ms", 99.80, 12, "2024-Q3");
            case "notification-hub" -> new SLAComplianceResponse("notification-hub", 99.91, "78ms", 99.97, 1, "2024-Q3");
            default -> new SLAComplianceResponse(serviceId, 0.0, "N/A", 0.0, -1, "N/A");
        };
    }

    @Tool(description = "Retrieve the security audit trail for a customer, showing recent access and configuration events.")
    public List<AuditEvent> getAuditTrail(
            @ToolArg(description = "Customer ID formatted as CUST-XXXX")
            @NotNull
            @Pattern(regexp = "^CUST-[0-9]{4,8}$")
            String customerId) {

        return "CUST-4091".equals(customerId)
                ? List.of(
                        new AuditEvent("2024-08-17T09:14:00Z", "admin@acme.com", "LOGIN", "console", "SUCCESS"),
                        new AuditEvent("2024-08-17T09:15:30Z", "admin@acme.com", "UPDATE_POLICY", "iam/role-bindings", "SUCCESS"),
                        new AuditEvent("2024-08-17T09:22:10Z", "ci-bot@acme.com", "DEPLOY", "us-east-1/prod-cluster", "SUCCESS"),
                        new AuditEvent("2024-08-17T10:01:45Z", "ops@acme.com", "SCALE_UP", "us-east-1/worker-pool", "SUCCESS"),
                        new AuditEvent("2024-08-17T10:45:00Z", "unknown@external.io", "LOGIN", "console", "DENIED"))
                : List.of(new AuditEvent("N/A", "N/A", "NO_RECORDS", customerId, "NOT_FOUND"));
    }
}
