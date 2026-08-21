package com.example.eval;

import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
public class McpEvalClient {

    @Inject
    @McpClientName("mcp-under-test")
    McpClient mcpClient;

    @Inject
    ObjectMapper mapper;

    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        String argsJson = mapper.writeValueAsString(arguments);
        var result = mcpClient.executeTool(
                ToolExecutionRequest.builder()
                        .name(toolName)
                        .arguments(argsJson)
                        .build());
        return result.resultText();
    }

    public JsonNode callToolAsJson(String toolName, Map<String, Object> arguments) throws Exception {
        String text = callTool(toolName, arguments);
        if (text == null || text.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return mapper.valueToTree(Map.of("text", text));
        }
    }

    public int getToolCount() {
        return mcpClient.listTools().size();
    }
}
