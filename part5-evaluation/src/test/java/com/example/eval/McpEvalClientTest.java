package com.example.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McpEvalClientTest {
    @Test
    void discoversToolsBeforeExecutionAndPreservesJsonObjects() throws Exception {
        var calls = new ArrayList<String>();
        var client = client("{\"status\":\"ACTIVE\"}", false, calls);
        assertEquals("ACTIVE", client.callToolAsJson("getCustomerStatus", Map.of()).path("status").asText());
        assertEquals(List.of("listTools", "executeTool"), calls);
    }

    @Test
    void retainsEveryTextBlockAndEveryJsonAuditEvent() throws Exception {
        var logs = client("CPU healthy\nMemory normal\nNo incidents", false, new ArrayList<>())
                .callToolAsJson("getZoneHealthLogs", Map.of());
        assertEquals(3, logs.size());
        assertEquals("Memory normal", logs.get(1).asText());
        var audit = client("{\"action\":\"LOGIN\"}\n{\"action\":\"DEPLOY\"}", false, new ArrayList<>())
                .callToolAsJson("getAuditTrail", Map.of());
        assertTrue(audit.isArray());
        assertEquals(2, audit.size());
        assertEquals("DEPLOY", audit.get(1).path("action").asText());
    }

    @Test
    void preservesPrettyPrintedJsonAndJsonArrays() throws Exception {
        var object = client("{\n  \"status\": \"ACTIVE\"\n}", false, new ArrayList<>())
                .callToolAsJson("getCustomerStatus", Map.of());
        assertEquals("ACTIVE", object.path("status").asText());
        var array = client("[\"first\",\"second\"]", false, new ArrayList<>())
                .callToolAsJson("getZoneHealthLogs", Map.of());
        assertEquals(2, array.size());
    }

    @Test
    void rejectsToolErrorFlagBeforeParsingTheText() {
        var client = client("customerId: must match CUST format", true, new ArrayList<>());
        var error = assertThrows(McpEvalClient.ToolRejectedException.class,
                () -> client.callToolAsJson("getCustomerStatus", Map.of()));
        assertTrue(error.getMessage().contains("must match"));
    }

    private McpEvalClient client(String text, boolean error, List<String> calls) {
        var client = new McpEvalClient();
        client.mapper = new ObjectMapper();
        client.mcpClient = (McpClient) Proxy.newProxyInstance(McpClient.class.getClassLoader(),
                new Class<?>[] {McpClient.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "listTools" -> List.of();
                        case "executeTool" -> ToolExecutionResult.builder().isError(error).resultText(text).build();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        return client;
    }

    @Test
    void recognizesClientToolRejectionsButPreservesProtocolAndTransportFailures() {
        for (var failure : List.of(new ToolExecutionException("must match"),
                new ToolExecutionException("internal error", -32603),
                new RuntimeException(new java.io.IOException("Connection refused")))) {
            var client = client("", false, new ArrayList<>());
            client.mcpClient = (McpClient) Proxy.newProxyInstance(McpClient.class.getClassLoader(),
                    new Class<?>[] {McpClient.class}, (proxy, method, args) -> {
                        if (method.getName().equals("listTools")) return List.of();
                        throw failure;
                    });
            if (failure instanceof ToolExecutionException toolError && toolError.errorCode() == null) {
                assertThrows(McpEvalClient.ToolRejectedException.class,
                        () -> client.callToolAsJson("getCustomerStatus", Map.of()));
            } else {
                assertSame(failure, assertThrows(RuntimeException.class,
                        () -> client.callToolAsJson("getCustomerStatus", Map.of())));
            }
        }
    }
}
