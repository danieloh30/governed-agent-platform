package com.example.mcp.tools;

import java.util.List;

import com.example.mcp.model.CustomerStatusResponse;

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

        if ("CUST-4091".equals(customerId)) {
            return new CustomerStatusResponse(
                    "CUST-4091",
                    "ACTIVE",
                    "ENTERPRISE_TIER",
                    "US-EAST-1"
            );
        }

        return new CustomerStatusResponse(
                customerId,
                "NOT_FOUND",
                "UNKNOWN",
                "UNKNOWN"
        );
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
}
