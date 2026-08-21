package com.example.eval;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class EvalRunner {

    @Inject
    McpEvalClient client;

    @Inject
    ObjectMapper mapper;

    public EvalSuite loadSuite(String name) throws Exception {
        String path = "golden/" + name + ".json";
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("Suite not found: " + name);
            }
            return mapper.readValue(is, EvalSuite.class);
        }
    }

    public List<String> listSuites() {
        return List.of("tool-accuracy", "validation-boundary", "workflow-regression");
    }

    public EvalReport runSuite(String name) throws Exception {
        EvalSuite suite = loadSuite(name);
        List<EvalResult> results = new ArrayList<>();

        if (suite.cases() != null) {
            for (EvalCase evalCase : suite.cases()) {
                results.add(runCase(evalCase));
            }
        }

        if (suite.workflows() != null) {
            for (EvalSuite.WorkflowCase workflow : suite.workflows()) {
                results.addAll(runWorkflow(workflow));
            }
        }

        return EvalReport.from(suite.name(), results);
    }

    private EvalResult runCase(EvalCase evalCase) {
        long start = System.currentTimeMillis();
        try {
            JsonNode response = client.callTool(evalCase.tool(), evalCase.arguments());
            long latency = System.currentTimeMillis() - start;

            if (response.has("error")) {
                String errorMsg = response.path("error").path("message").asText("");
                if (evalCase.expectError()) {
                    if (evalCase.errorContains() != null && !errorMsg.contains(evalCase.errorContains())) {
                        return EvalResult.fail(evalCase.id(), evalCase.tool(), latency,
                                response.get("error"), null,
                                "Error message does not contain: " + evalCase.errorContains());
                    }
                    return EvalResult.pass(evalCase.id(), evalCase.tool(), latency, response.get("error"));
                }
                return EvalResult.fail(evalCase.id(), evalCase.tool(), latency,
                        response.get("error"), null, "Unexpected error: " + errorMsg);
            }

            JsonNode content = response.path("result").path("content");
            if (content.isMissingNode() || !content.isArray() || content.isEmpty()) {
                if (evalCase.expectError()) {
                    return EvalResult.fail(evalCase.id(), evalCase.tool(), latency,
                            null, null, "Expected error but got empty result");
                }
                return EvalResult.fail(evalCase.id(), evalCase.tool(), latency,
                        null, evalCase.expect(), "Empty content in response");
            }

            JsonNode actual = parseContent(content);

            if (evalCase.expectError()) {
                boolean isError = content.size() > 0 && content.get(0).path("isError").asBoolean(false);
                if (isError) {
                    String errorText = content.get(0).path("text").asText("");
                    if (evalCase.errorContains() != null && !errorText.contains(evalCase.errorContains())) {
                        return EvalResult.fail(evalCase.id(), evalCase.tool(), latency,
                                actual, null,
                                "Error text does not contain: " + evalCase.errorContains());
                    }
                    return EvalResult.pass(evalCase.id(), evalCase.tool(), latency, actual);
                }
                return EvalResult.fail(evalCase.id(), evalCase.tool(), latency,
                        actual, null, "Expected error but got success");
            }

            if (evalCase.expectCount() != null) {
                int count = actual.isArray() ? actual.size() : 1;
                if (count != evalCase.expectCount()) {
                    return EvalResult.fail(evalCase.id(), evalCase.tool(), latency,
                            mapper.valueToTree(Map.of("count", count)),
                            mapper.valueToTree(Map.of("count", evalCase.expectCount())),
                            "Expected " + evalCase.expectCount() + " items, got " + count);
                }
                return EvalResult.pass(evalCase.id(), evalCase.tool(), latency, actual);
            }

            if (evalCase.expect() != null) {
                String mismatch = compareFields(actual, evalCase.expect());
                if (mismatch != null) {
                    return EvalResult.fail(evalCase.id(), evalCase.tool(), latency,
                            actual, evalCase.expect(), mismatch);
                }
            }

            return EvalResult.pass(evalCase.id(), evalCase.tool(), latency, actual);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            if (evalCase.expectError()) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (evalCase.errorContains() == null || msg.contains(evalCase.errorContains())) {
                    return EvalResult.pass(evalCase.id(), evalCase.tool(), latency,
                            mapper.valueToTree(Map.of("exception", msg)));
                }
            }
            return EvalResult.fail(evalCase.id(), evalCase.tool(), latency,
                    null, evalCase.expect(),
                    "Exception: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private List<EvalResult> runWorkflow(EvalSuite.WorkflowCase workflow) {
        List<EvalResult> results = new ArrayList<>();
        Map<String, String> extractions = new HashMap<>();

        for (EvalCase step : workflow.steps()) {
            Map<String, Object> resolvedArgs = new HashMap<>();
            for (var entry : step.arguments().entrySet()) {
                String val = String.valueOf(entry.getValue());
                for (var ext : extractions.entrySet()) {
                    val = val.replace("${" + ext.getKey() + "}", ext.getValue());
                }
                resolvedArgs.put(entry.getKey(), val);
            }

            EvalCase resolved = new EvalCase(
                    workflow.id() + "/" + step.id(),
                    step.description(),
                    step.tool(),
                    resolvedArgs,
                    step.expect(),
                    step.expectError(),
                    step.expectCount(),
                    step.errorContains(),
                    step.extractField(),
                    step.extractAs());

            EvalResult result = runCase(resolved);
            results.add(result);

            if (result.passed() && step.extractField() != null && step.extractAs() != null && result.actual() != null) {
                JsonNode val = result.actual().path(step.extractField());
                if (!val.isMissingNode()) {
                    extractions.put(step.extractAs(), val.asText());
                }
            }
        }
        return results;
    }

    private JsonNode parseContent(JsonNode content) {
        if (content.isArray() && content.size() == 1) {
            String text = content.get(0).path("text").asText("");
            try {
                return mapper.readTree(text);
            } catch (Exception e) {
                return content.get(0);
            }
        }
        if (content.isArray()) {
            var arr = mapper.createArrayNode();
            for (JsonNode item : content) {
                String text = item.path("text").asText("");
                try {
                    arr.add(mapper.readTree(text));
                } catch (Exception e) {
                    arr.add(item);
                }
            }
            return arr;
        }
        return content;
    }

    private String compareFields(JsonNode actual, JsonNode expected) {
        Iterator<String> fields = expected.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            JsonNode expectedVal = expected.get(field);
            JsonNode actualVal = actual.path(field);

            if (actualVal.isMissingNode()) {
                return "Missing field: " + field;
            }

            if (expectedVal.isNumber() && actualVal.isNumber()) {
                if (expectedVal.doubleValue() != actualVal.doubleValue()) {
                    return "Field " + field + ": expected " + expectedVal + ", got " + actualVal;
                }
            } else if (!expectedVal.asText().equals(actualVal.asText())) {
                return "Field " + field + ": expected " + expectedVal.asText() + ", got " + actualVal.asText();
            }
        }
        return null;
    }
}
