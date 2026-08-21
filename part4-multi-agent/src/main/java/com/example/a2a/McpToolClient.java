package com.example.a2a;

import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import io.quarkus.logging.Log;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
public class McpToolClient {

    @Inject
    @McpClientName("part1")
    McpClient mcpClient;

    @Inject
    ObjectMapper mapper;

    public String callTool(String toolName, Map<String, String> arguments) {
        try {
            String argsJson = mapper.writeValueAsString(arguments);
            var result = mcpClient.executeTool(
                    ToolExecutionRequest.builder()
                            .name(toolName)
                            .arguments(argsJson)
                            .build());
            String text = result.resultText();
            Log.infof("MCP tool '%s' returned %d chars", toolName, text != null ? text.length() : 0);
            return text;
        } catch (Exception e) {
            Log.warnf("MCP tool '%s' call failed: %s", toolName, e.getMessage());
            return null;
        }
    }
}
