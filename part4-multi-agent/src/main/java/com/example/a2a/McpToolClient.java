package com.example.a2a;

import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class McpToolClient {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolClient.class);

    @Inject
    @McpClientName("part1")
    McpClient mcpClient;

    private final ObjectMapper mapper = new ObjectMapper();

    public String callTool(String toolName, Map<String, String> arguments) {
        try {
            String argsJson = mapper.writeValueAsString(arguments);
            var result = mcpClient.executeTool(
                    ToolExecutionRequest.builder()
                            .name(toolName)
                            .arguments(argsJson)
                            .build());
            String text = result.resultText();
            LOG.info("MCP tool '{}' returned {} chars", toolName, text != null ? text.length() : 0);
            return text;
        } catch (Exception e) {
            LOG.warn("MCP tool '{}' call failed: {}", toolName, e.getMessage());
            return null;
        }
    }
}
