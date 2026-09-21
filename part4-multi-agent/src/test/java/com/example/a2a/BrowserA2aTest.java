package com.example.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BrowserA2aTest {
    private static final String ORIGIN = "http://localhost:8889";
    @TestHTTPResource URI base;
    @Inject ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void browserCanDiscoverAgentAndGovernance() throws Exception {
        var card = get("/.well-known/agent-card.json");
        assertEquals("enterprise-workflow-agent", json.readTree(card.body()).path("name").asText());
        assertFalse(json.readTree(card.body()).path("skills").isEmpty());
        var governance = get("/api/governance");
        assertTrue(json.readTree(governance.body()).has("requiresApproval"));
    }

    @Test
    void browserPreflightAllowsA2aAndApprovalRequests() throws Exception {
        for (String path : new String[] {"/", "/api/tasks/example/approve", "/api/tasks/example/reject"}) {
            var response = http.send(HttpRequest.newBuilder(base.resolve(path))
                    .header("Origin", ORIGIN)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,a2a-version")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertBrowserResponse(response);
            String headers = response.headers().firstValue("access-control-allow-headers").orElseThrow().toLowerCase();
            assertTrue(headers.contains("content-type"));
            assertTrue(headers.contains("a2a-version"));
            assertTrue(response.headers().firstValue("access-control-allow-methods").orElseThrow().contains("POST"));
        }
    }

    @Test
    void browserCanApproveAndRejectServerGeneratedTasks() throws Exception {
        for (String decision : new String[] {"approve", "reject"}) {
            var task = submit("migrate-schema --database production --table users --changes add-column-email");
            assertEquals("TASK_STATE_INPUT_REQUIRED", task.path("status").path("state").asText());
            String id = task.path("id").asText();
            assertFalse(id.isBlank());
            var response = post("/api/tasks/" + id + "/" + decision, "{\"reason\":\"Browser regression test\"}");
            assertEquals(id, response.path("id").asText());
            assertEquals(decision.equals("approve") ? "completed" : "failed",
                    response.path("status").path("state").asText());
        }
    }

    @Test
    void browserTaskStillObeysBlockedOperationPolicy() throws Exception {
        var task = submit("drop-database --target production");
        assertEquals("TASK_STATE_FAILED", task.path("status").path("state").asText());
    }

    private JsonNode submit(String command) throws Exception {
        var response = post("/", """
                {"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{
                  "message":{"messageId":"%s","role":"ROLE_USER","parts":[{"text":"%s"}]},
                  "configuration":{"returnImmediately":false}}}
                """.formatted(UUID.randomUUID(), command));
        assertFalse(response.has("error"), response.toString());
        return response.path("result").path("task");
    }

    private JsonNode post(String path, String body) throws Exception {
        var response = http.send(HttpRequest.newBuilder(base.resolve(path))
                .header("Origin", ORIGIN).header("Content-Type", "application/json")
                .header("A2A-Version", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertBrowserResponse(response);
        return json.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(base.resolve(path))
                .header("Origin", ORIGIN).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertBrowserResponse(response);
        return response;
    }

    private void assertBrowserResponse(HttpResponse<String> response) {
        assertEquals(200, response.statusCode());
        assertEquals(ORIGIN, response.headers().firstValue("access-control-allow-origin").orElseThrow());
    }
}
