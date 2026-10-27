package com.example.sandbox;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ReconciliationEndpointTest {
    @TestHTTPResource URI base;
    @Inject ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void policyAndConsoleAreServedWithoutAnOpenSandboxConnection() throws Exception {
        var response = get("/api/policy");
        assertEquals(200, response.statusCode());
        var policy = json.readTree(response.body());
        assertEquals("DENY", policy.path("networkDefault").asText());
        assertEquals(1, policy.path("maxConcurrentRuns").asInt());
        assertTrue(get("/").body().contains("Governed sandbox execution"));
    }

    @Test
    void rejectsArbitraryCommandsBeforeConnectingToOpenSandbox() throws Exception {
        for (String body : new String[] {"{}", "null", "{\"scenario\":\"reconcile; whoami\"}"}) {
            var response = post("/api/runs", body, null);
            assertEquals(400, response.statusCode());
            assertTrue(json.readTree(response.body()).has("error"));
        }
    }

    @Test
    void mcpDiscoveryAndArgumentValidationWorkWithoutProvisioning() throws Exception {
        var init = post("/mcp", """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"part7-test","version":"1"}}}
            """, null);
        assertEquals(200, init.statusCode());
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertEquals(202, post("/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session).statusCode());
        var listed = json.readTree(post("/mcp", """
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
            """, session).body());
        assertEquals("reconcileSettlements", listed.path("result").path("tools").get(0).path("name").asText());
        var rejected = json.readTree(post("/mcp", """
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"reconcileSettlements","arguments":{"scenario":"sh -c id"}}}
            """, session).body());
        assertTrue(rejected.path("result").path("isError").asBoolean(), rejected.toString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String session) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream");
        if (session != null) request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-03-26");
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
