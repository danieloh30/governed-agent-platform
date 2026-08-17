package com.example.mcp.model;

public record SLAComplianceResponse(
        String serviceId,
        double compliancePct,
        String p99Latency,
        double uptimePct,
        int violationCount,
        String period
) {
}
