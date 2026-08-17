package com.example.mcp.model;

public record AuditEvent(
        String timestamp,
        String actor,
        String action,
        String resource,
        String outcome
) {
}
