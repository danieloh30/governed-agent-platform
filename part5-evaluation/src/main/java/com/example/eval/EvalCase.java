package com.example.eval;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String id,
        String description,
        String tool,
        Map<String, Object> arguments,
        JsonNode expect,
        boolean expectError,
        Integer expectCount,
        String errorContains,
        String extractField,
        String extractAs) {
}
