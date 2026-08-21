package com.example.eval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class McpEvalClient {

    @ConfigProperty(name = "eval.mcp.endpoint", defaultValue = "http://localhost:8080/mcp")
    String mcpEndpoint;

    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger rpcId = new AtomicInteger(0);

    public JsonNode initialize() throws Exception {
        return mcpCall("initialize", mapper.createObjectNode()
                .put("protocolVersion", "2025-03-26")
                .putObject("capabilities")
                .objectNode()
                .setAll(Map.of(
                        "protocolVersion", mapper.valueToTree("2025-03-26"),
                        "capabilities", mapper.createObjectNode(),
                        "clientInfo", mapper.createObjectNode()
                                .put("name", "eval-runner")
                                .put("version", "1.0"))));
    }

    public JsonNode listTools() throws Exception {
        return mcpCall("tools/list", mapper.createObjectNode());
    }

    public JsonNode callTool(String name, Map<String, Object> arguments) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", mapper.valueToTree(arguments));
        return mcpCall("tools/call", params);
    }

    private JsonNode mcpCall(String method, JsonNode params) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", rpcId.incrementAndGet());
        body.put("method", method);

        if ("initialize".equals(method)) {
            ObjectNode initParams = mapper.createObjectNode();
            initParams.put("protocolVersion", "2025-03-26");
            initParams.putObject("capabilities");
            initParams.putObject("clientInfo")
                    .put("name", "eval-runner")
                    .put("version", "1.0");
            body.set("params", initParams);
        } else {
            body.set("params", params);
        }

        var request = HttpRequest.newBuilder()
                .uri(URI.create(mcpEndpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();

        if (responseBody.contains("data: ")) {
            String data = responseBody.lines()
                    .filter(l -> l.startsWith("data: "))
                    .reduce((a, b) -> b)
                    .map(l -> l.substring(6))
                    .orElse(responseBody);
            return mapper.readTree(data);
        }
        return mapper.readTree(responseBody);
    }
}
