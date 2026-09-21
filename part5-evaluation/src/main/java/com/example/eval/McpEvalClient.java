package com.example.eval;

import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.exception.ToolExecutionException;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;

@ApplicationScoped
public class McpEvalClient {

    @Inject
    @McpClientName("mcp-under-test")
    McpClient mcpClient;

    @Inject
    ObjectMapper mapper;

    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        // The managed client caches discovery and refreshes it on tool-list changes.
        mcpClient.listTools();
        String argsJson = mapper.writeValueAsString(arguments);
        try {
            var result = mcpClient.executeTool(
                ToolExecutionRequest.builder()
                        .name(toolName)
                        .arguments(argsJson)
                        .build());
            if (result.isError()) {
                throw new ToolRejectedException(result.resultText());
            }
            return result.resultText();
        } catch (ToolExecutionException e) {
            // The current client throws for isError results. Its protocol
            // exceptions carry an error code; transport failures use other types.
            if (e.errorCode() != null) throw e;
            throw new ToolRejectedException(e.getMessage());
        }
    }

    public JsonNode callToolAsJson(String toolName, Map<String, Object> arguments) throws Exception {
        String text = callTool(toolName, arguments);
        if (text == null || text.isBlank()) {
            return mapper.createObjectNode();
        }
        JsonNode parsed = parseText(text);
        if (!parsed.isTextual()) return parsed;

        // Part 1 encodes list items as separate MCP text blocks. LangChain4j
        // joins those blocks with newlines (plain log lines or JSON audit events).
        // Strict parsing above prevents Jackson from silently keeping only the
        // first audit event when several JSON objects arrive together.
        var lines = text.lines().toList();
        if (lines.size() > 1) {
            var items = mapper.createArrayNode();
            lines.forEach(line -> items.add(parseText(line)));
            return items;
        }
        return mapper.valueToTree(Map.of("text", text));
    }

    private JsonNode parseText(String text) {
        try {
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
        } catch (JsonProcessingException e) {
            return mapper.getNodeFactory().textNode(text);
        }
    }

    public static class ToolRejectedException extends Exception {
        public ToolRejectedException(String message) {
            super(message == null ? "MCP tool returned an error" : message);
        }
    }

    public int getToolCount() {
        return mcpClient.listTools().size();
    }
}
