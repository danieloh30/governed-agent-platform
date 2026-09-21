package com.example.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BrowserMcpTest {
    @TestHTTPResource URI base;
    @Inject ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void browserPreflightAllowsMcpSessionHeaders() throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/mcp"))
                .header("Origin", "http://localhost:8887")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,mcp-session-id,mcp-protocol-version")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:8887", response.headers().firstValue("access-control-allow-origin").orElseThrow());
        assertTrue(response.headers().firstValue("access-control-allow-headers").orElseThrow().toLowerCase().contains("mcp-session-id"));
    }

    @Test
    void browserCanInitializeAndDiscoverToolsWithItsSession() throws Exception {
        var init = post("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"browser-test","version":"1"}}}
            """, null);
        assertEquals(200, init.statusCode());
        assertTrue(init.headers().firstValue("access-control-expose-headers").orElseThrow().toLowerCase().contains("mcp-session-id"));
        String session = init.headers().firstValue("mcp-session-id").orElseThrow();
        var initialized = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session);
        assertTrue(initialized.statusCode() >= 200 && initialized.statusCode() < 300);
        var listed = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", session);
        assertEquals(200, listed.statusCode());
        var tools = json.readTree(listed.body()).path("result").path("tools");
        assertEquals(5, tools.size());
        assertTrue(tools.toString().contains("getCustomerStatus"));
        var rejected = post("""
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getCustomerStatus","arguments":{"customerId":"INVALID"}}}
            """, session);
        var rejection = json.readTree(rejected.body());
        assertFalse(rejection.has("error"), "Validation must not become an internal protocol error");
        assertTrue(rejection.path("result").path("isError").asBoolean());
        assertTrue(rejection.path("result").path("content").get(0).path("text").asText().contains("must match"));
    }

    private HttpResponse<String> post(String body, String session) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/mcp"))
                .header("Origin", "http://localhost:8887").header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (session != null) request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-03-26");
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
