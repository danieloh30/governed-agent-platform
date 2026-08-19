package com.example.mcp.model;

public record OrderStatusResponse(
        String orderId,
        String status,
        int itemCount,
        String totalAmount,
        String estimatedDelivery,
        String warehouse
) {
}
