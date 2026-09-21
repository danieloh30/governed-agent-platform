package com.example.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvalRunnerTest {
    @Test
    void validationSuitePassesOnlyForExpectedToolRejections() throws Exception {
        var runner = runner(new McpEvalClient() {
            @Override
            public JsonNode callToolAsJson(String tool, Map<String, Object> arguments) throws Exception {
                throw new ToolRejectedException(tool.equals("getZoneHealthLogs") || tool.equals("getSLACompliance")
                        ? "size must be within the limit" : "must match the required format");
            }
        });
        var report = runner.runSuite("validation-boundary");
        assertEquals(8, report.passed());
    }

    @Test
    void networkFailureCannotPassValidationEvenWithMatchingErrorText() throws Exception {
        var runner = runner(new McpEvalClient() {
            @Override
            public JsonNode callToolAsJson(String tool, Map<String, Object> arguments) throws Exception {
                throw new IOException("Connection failure: must match; size must be");
            }
        });
        assertEquals(8, runner.runSuite("validation-boundary").failed());
    }

    private EvalRunner runner(McpEvalClient client) {
        var runner = new EvalRunner();
        runner.mapper = new ObjectMapper();
        runner.client = client;
        return runner;
    }
}
