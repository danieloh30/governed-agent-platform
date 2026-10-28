package com.example.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Fixed requests and a local read-only tool used by the compatibility exercise. */
public final class ResponsesExamples {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ResponsesExamples() {}

    public static ObjectNode text(String model, boolean stream) {
        return JSON.createObjectNode().put("model", model).put("input", "Summarize Acme support status.")
            .put("stream", stream).put("store", false);
    }

    public static ObjectNode tool(String model) {
        ObjectNode request = text(model, false);
        ObjectNode tool = request.putArray("tools").addObject().put("type", "function")
            .put("name", "get_support_status").put("description", "Read synthetic customer support status")
            .put("strict", true);
        ObjectNode parameters = tool.putObject("parameters").put("type", "object").put("additionalProperties", false);
        parameters.putObject("properties").putObject("customer_id").put("type", "string");
        parameters.putArray("required").add("customer_id");
        return request;
    }

    public static ObjectNode continueWithToolResult(ObjectNode original, JsonNode call) {
        // Treat model-produced function calls as untrusted input before dispatch.
        try {
            JsonNode arguments = JSON.readTree(call.path("arguments").asText());
            if (!"function_call".equals(call.path("type").asText())
                    || !"get_support_status".equals(call.path("name").asText())
                    || !call.path("call_id").isTextual() || call.path("call_id").asText().isBlank()
                    || arguments == null || !arguments.isObject() || arguments.size() != 1
                    || !"CUST-4091".equals(arguments.path("customer_id").asText())) {
                throw new IllegalArgumentException("Refusing unknown function or arguments");
            }
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid function arguments", invalid); }
        ObjectNode next = original.deepCopy();
        var input = next.putArray("input");
        if (original.path("input").isTextual()) {
            input.addObject().put("role", "user").put("content", original.path("input").asText());
        } else {
            original.path("input").forEach(item -> input.add(item.deepCopy()));
        }
        input.add(call.deepCopy());
        input.addObject().put("type", "function_call_output").put("call_id", call.path("call_id").asText())
            .put("output", "{\"customer_id\":\"CUST-4091\",\"status\":\"ACTIVE\"}");
        return next;
    }
}
