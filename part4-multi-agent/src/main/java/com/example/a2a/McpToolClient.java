package com.example.a2a;

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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class McpToolClient {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolClient.class);

    @ConfigProperty(name = "mcp.server.url", defaultValue = "http://localhost:8080/mcp")
    String mcpServerUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requestId = new AtomicInteger(1);

    private volatile String sessionId;

    private synchronized String ensureSession() {
        if (sessionId != null) return sessionId;
        try {
            ObjectNode init = mapper.createObjectNode();
            init.put("jsonrpc", "2.0");
            init.put("id", requestId.getAndIncrement());
            init.put("method", "initialize");
            ObjectNode params = init.putObject("params");
            params.put("protocolVersion", "2025-03-26");
            params.putObject("capabilities");
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "a2a-flow-server");
            clientInfo.put("version", "1.0");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mcpServerUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(init)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            sessionId = response.headers().firstValue("mcp-session-id").orElse(null);
            LOG.info("MCP session established: {}", sessionId);
            return sessionId;
        } catch (Exception e) {
            LOG.warn("Failed to establish MCP session: {}", e.getMessage());
            return null;
        }
    }

    public String callTool(String toolName, Map<String, String> arguments) {
        try {
            String sid = ensureSession();

            ObjectNode rpc = mapper.createObjectNode();
            rpc.put("jsonrpc", "2.0");
            rpc.put("id", requestId.getAndIncrement());
            rpc.put("method", "tools/call");
            ObjectNode params = rpc.putObject("params");
            params.put("name", toolName);
            ObjectNode args = params.putObject("arguments");
            arguments.forEach(args::put);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(mcpServerUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", "2025-03-26")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpc)));

            if (sid != null) {
                reqBuilder.header("mcp-session-id", sid);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            String body = response.body();

            // MCP Streamable HTTP returns SSE — extract the data line
            if (body.contains("data: ")) {
                String[] lines = body.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (lines[i].startsWith("data: ")) {
                        body = lines[i].substring(6);
                        break;
                    }
                }
            }

            JsonNode result = mapper.readTree(body);
            JsonNode content = result.path("result").path("content");
            if (content.isArray() && !content.isEmpty()) {
                String text = content.get(0).path("text").asText();
                LOG.info("MCP tool '{}' returned {} chars", toolName, text.length());
                return text;
            }

            LOG.warn("MCP tool '{}' returned unexpected format", toolName);
            return null;
        } catch (Exception e) {
            LOG.warn("MCP tool '{}' call failed: {}", toolName, e.getMessage());
            return null;
        }
    }
}
