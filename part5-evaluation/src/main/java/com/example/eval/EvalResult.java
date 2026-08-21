package com.example.eval;

import com.fasterxml.jackson.databind.JsonNode;

public record EvalResult(
        String caseId,
        String tool,
        boolean passed,
        long latencyMs,
        JsonNode actual,
        JsonNode expected,
        String error) {

    public static EvalResult pass(String caseId, String tool, long latencyMs, JsonNode actual) {
        return new EvalResult(caseId, tool, true, latencyMs, actual, null, null);
    }

    public static EvalResult fail(String caseId, String tool, long latencyMs, JsonNode actual, JsonNode expected, String error) {
        return new EvalResult(caseId, tool, false, latencyMs, actual, expected, error);
    }
}
