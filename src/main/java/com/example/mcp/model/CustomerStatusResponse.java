package com.example.mcp.model;

public record CustomerStatusResponse(
        String customerId,
        String status,
        String tier,
        String primaryRegion
) {
}
