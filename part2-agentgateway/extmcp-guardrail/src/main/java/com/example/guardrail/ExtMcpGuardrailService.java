package com.example.guardrail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import com.example.guardrail.grpc.AuthorizationError;
import com.example.guardrail.grpc.ExtMcp;
import com.example.guardrail.grpc.McpHeader;
import com.example.guardrail.grpc.McpRequest;
import com.example.guardrail.grpc.McpRequestResult;
import com.example.guardrail.grpc.McpResponse;
import com.example.guardrail.grpc.McpResponseResult;
import com.example.guardrail.grpc.Pass;
import com.google.protobuf.ByteString;

import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;

@GrpcService
public class ExtMcpGuardrailService implements ExtMcp {

    private static final Pattern DANGEROUS_HEADER = Pattern.compile(
            "(?i)^(x-mcp-|x-forwarded-|x-real-ip)");

    private static final int MAX_HEADER_LENGTH = 256;

    private static final List<String> BLOCKED_PATTERNS = List.of(
            "__proto__", "constructor", "../", "eval(", "exec(", "<script");

    @Override
    public Uni<McpRequestResult> checkRequest(McpRequest request) {
        if (!"tools/call".equals(request.getMethod())) {
            return passRequest();
        }

        String headerError = sanitizeHeaders(request.getHeadersList());
        if (headerError != null) {
            return denyRequest("header sanitization failed: " + headerError);
        }

        if (request.hasMcpRequest()) {
            String paramsJson = request.getMcpRequest().toStringUtf8();
            String poisonError = checkToolPoisoning(paramsJson);
            if (poisonError != null) {
                return denyRequest("tool poisoning detected: " + poisonError);
            }
        }

        return passRequest();
    }

    @Override
    public Uni<McpResponseResult> checkResponse(McpResponse response) {
        if (!"tools/list".equals(response.getMethod())) {
            return passResponse();
        }

        String original = response.getMcpResponse().toStringUtf8();
        String mutated = original.replace("\"description\":\"",
                "\"description\":\"[guardrail-verified] ");

        return Uni.createFrom().item(McpResponseResult.newBuilder()
                .setMutated(ByteString.copyFrom(mutated, StandardCharsets.UTF_8))
                .build());
    }

    private String sanitizeHeaders(List<McpHeader> headers) {
        for (McpHeader header : headers) {
            String key = header.getKey();
            if (DANGEROUS_HEADER.matcher(key).find()) {
                String value = header.getValue().toStringUtf8();

                if (value.contains("\r") || value.contains("\n")) {
                    return "header '" + key + "' contains CRLF injection attempt";
                }

                if (value.length() > MAX_HEADER_LENGTH) {
                    return "header '" + key + "' exceeds maximum length ("
                            + MAX_HEADER_LENGTH + " bytes)";
                }
            }
        }
        return null;
    }

    private String checkToolPoisoning(String paramsJson) {
        String lower = paramsJson.toLowerCase();
        for (String pattern : BLOCKED_PATTERNS) {
            if (lower.contains(pattern)) {
                return "params contain blocked pattern '" + pattern + "'";
            }
        }
        return null;
    }

    private Uni<McpRequestResult> passRequest() {
        return Uni.createFrom().item(McpRequestResult.newBuilder()
                .setPass(Pass.getDefaultInstance())
                .build());
    }

    private Uni<McpResponseResult> passResponse() {
        return Uni.createFrom().item(McpResponseResult.newBuilder()
                .setPass(Pass.getDefaultInstance())
                .build());
    }

    private Uni<McpRequestResult> denyRequest(String reason) {
        return Uni.createFrom().item(McpRequestResult.newBuilder()
                .setError(AuthorizationError.newBuilder()
                        .setCode(AuthorizationError.Code.PERMISSION_DENIED)
                        .setReason(reason)
                        .build())
                .build());
    }
}
