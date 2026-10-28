package com.example.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Deliberately bounded, deterministic subset of the Open Responses HTTP contract. */
@ApplicationScoped
public class ResponsesService {
    @Inject ObjectMapper json;
    @Inject ModelService model;
    private static final Set<String> FIELDS = Set.of("model", "input", "stream", "store", "tools");

    public record Result(int status, Object body, boolean stream) {}

    public Result respond(JsonNode request) {
        try { validate(request); }
        catch (IllegalArgumentException invalid) {
            return new Result(400, new ModelApi.ErrorResponse(new ModelApi.ErrorDetail(
                invalid.getMessage(), "invalid_request_error", "unsupported_lab_request")), false);
        }
        ModelApi.Result failure = model.attempt(request.path("model").asText());
        if (failure != null) return new Result(failure.status(), failure.body(), false);
        String backend = model.state().backend();
        ObjectNode item = json.createObjectNode();
        boolean hasToolOutput = request.path("input").isArray()
            && request.path("input").findValuesAsText("type").contains("function_call_output");
        if (request.path("tools").size() > 0 && !hasToolOutput) {
            item.put("type", "function_call").put("id", "fc_" + UUID.randomUUID())
                .put("call_id", "call_" + UUID.randomUUID()).put("name", "get_support_status")
                .put("arguments", "{\"customer_id\":\"CUST-4091\"}").put("status", "completed");
        } else {
            item.put("type", "message").put("id", "msg_" + UUID.randomUUID())
                .put("role", "assistant").put("status", "completed").put("phase", "final_answer");
            var text = item.putArray("content").addObject().put("type", "output_text")
                .put("text", backend + ": " + (hasToolOutput ? "CUST-4091 is ACTIVE (synthetic tool result)."
                    : "Acme support request received (stub; no inference)."));
            text.putArray("annotations");
        }
        ObjectNode response = envelope(request, item);
        return new Result(200, response, request.path("stream").asBoolean(false));
    }

    private void validate(JsonNode request) {
        require(request != null && request.isObject(), "Expected a JSON object");
        request.fieldNames().forEachRemaining(name -> require(FIELDS.contains(name), "Unsupported lab field: " + name));
        require(request.path("model").isTextual() && !request.path("model").asText().isBlank(), "model is required");
        require(!request.has("stream") || request.path("stream").isBoolean(), "stream must be boolean");
        require(!request.has("store") || (request.path("store").isBoolean() && !request.path("store").asBoolean()), "Only store:false is supported; replay full history");
        JsonNode input = request.path("input");
        if (input.isTextual()) require(!input.asText().isBlank(), "input must not be blank");
        else {
            require(input.isArray() && !input.isEmpty(), "input must be text or a nonempty item array");
            Set<String> calls = new HashSet<>(), outputs = new HashSet<>();
            for (JsonNode item : input) {
                require(item.isObject(), "Each input item must be an object");
                switch (item.path("type").asText("message")) {
                    case "message" -> {
                        String role = item.path("role").asText();
                        require(Set.of("user", "assistant", "system", "developer").contains(role), "Unsupported message role");
                        JsonNode content = item.path("content");
                        if (content.isTextual()) require(!content.asText().isBlank(), "Message content must not be blank");
                        else {
                            require(content.isArray() && !content.isEmpty(), "Only text message content is supported");
                            for (JsonNode part : content) {
                                require(Set.of("input_text", "output_text").contains(part.path("type").asText())
                                    && part.path("text").isTextual() && !part.path("text").asText().isBlank(), "Only nonblank text parts are supported");
                            }
                        }
                    }
                    case "function_call" -> {
                        require("get_support_status".equals(item.path("name").asText()), "Unknown function call");
                        require(item.path("call_id").isTextual() && !item.path("call_id").asText().isBlank(), "call_id is required");
                        require(calls.add(item.path("call_id").asText()), "Duplicate function call ID");
                        JsonNode arguments = parse(item.path("arguments"));
                        require(arguments.size() == 1 && "CUST-4091".equals(arguments.path("customer_id").asText()), "Only the synthetic customer is supported");
                    }
                    case "function_call_output" -> {
                        String id = item.path("call_id").asText();
                        require(calls.contains(id) && outputs.add(id), "Tool output must reference an earlier, unmatched call_id in input");
                        JsonNode output = parse(item.path("output"));
                        require("CUST-4091".equals(output.path("customer_id").asText()) && "ACTIVE".equals(output.path("status").asText()), "Expected the synthetic Java tool result");
                    }
                    default -> throw new IllegalArgumentException("Unsupported input item type");
                }
            }
            require(calls.equals(outputs), "Replay each function call together with its output");
        }
        if (request.has("tools")) {
            JsonNode tools = request.path("tools");
            require(tools.isArray() && tools.size() <= 1, "Only one function tool is supported");
            for (JsonNode tool : tools) require("function".equals(tool.path("type").asText())
                && "get_support_status".equals(tool.path("name").asText())
                && tool.path("parameters").isObject(), "Only get_support_status with a parameter schema is supported");
        }
    }

    private JsonNode parse(JsonNode value) {
        require(value.isTextual(), "Function arguments/output must be JSON strings");
        try {
            JsonNode parsed = json.readTree(value.asText());
            require(parsed != null && parsed.isObject(), "Expected a JSON object in function arguments/output");
            return parsed;
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid JSON in function arguments/output"); }
    }

    private ObjectNode envelope(JsonNode request, ObjectNode item) {
        long now = Instant.now().getEpochSecond();
        ObjectNode response = json.createObjectNode().put("id", "resp_" + UUID.randomUUID())
            .put("object", "response").put("created_at", now).put("completed_at", now)
            .put("status", "completed").put("model", request.path("model").asText())
            .put("store", false).put("background", false).put("parallel_tool_calls", false)
            .put("tool_choice", "auto").put("truncation", "disabled").put("temperature", 1.0)
            .put("top_p", 1.0).put("presence_penalty", 0.0).put("frequency_penalty", 0.0)
            .put("top_logprobs", 0).put("service_tier", "default");
        for (String key : Set.of("previous_response_id", "instructions", "error", "incomplete_details", "reasoning",
                "max_output_tokens", "max_tool_calls", "safety_identifier", "prompt_cache_key")) response.putNull(key);
        response.putObject("metadata");
        response.putObject("text").putObject("format").put("type", "text");
        var tools = response.putArray("tools");
        for (JsonNode definition : request.path("tools")) {
            ObjectNode tool = definition.deepCopy();
            if (!tool.has("description")) tool.put("description", "Read the synthetic Acme customer status");
            if (!tool.has("strict")) tool.put("strict", false);
            tools.add(tool);
        }
        response.putArray("output").add(item);
        ObjectNode usage = response.putObject("usage").put("input_tokens", 12).put("output_tokens", 8).put("total_tokens", 20);
        usage.putObject("input_tokens_details").put("cached_tokens", 0);
        usage.putObject("output_tokens_details").put("reasoning_tokens", 0);
        return response;
    }

    /** Semantic SSE frames; deterministic stubs intentionally have no token-generation delays. */
    public String events(ObjectNode completed) {
        StringBuilder frames = new StringBuilder();
        ObjectNode progress = completed.deepCopy().put("status", "in_progress").putNull("completed_at").putNull("usage");
        progress.putArray("output");
        int sequence = 0;
        frame(frames, event("response.created", sequence++).set("response", progress));
        frame(frames, event("response.in_progress", sequence++).set("response", progress));
        ObjectNode item = (ObjectNode) completed.path("output").get(0);
        ObjectNode started = item.deepCopy().put("status", "in_progress");
        boolean message = "message".equals(item.path("type").asText());
        if (message) started.putArray("content"); else started.put("arguments", "");
        frame(frames, event("response.output_item.added", sequence++).put("output_index", 0).set("item", started));
        if (message) {
            JsonNode part = item.path("content").get(0);
            ObjectNode empty = ((ObjectNode) part).deepCopy().put("text", "");
            frame(frames, contentEvent("response.content_part.added", sequence++, item).set("part", empty));
            frame(frames, contentEvent("response.output_text.delta", sequence++, item).put("delta", part.path("text").asText()));
            frame(frames, contentEvent("response.output_text.done", sequence++, item).put("text", part.path("text").asText()));
            frame(frames, contentEvent("response.content_part.done", sequence++, item).set("part", part));
        } else {
            frame(frames, event("response.function_call_arguments.delta", sequence++).put("item_id", item.path("id").asText())
                .put("output_index", 0).put("delta", item.path("arguments").asText()));
            frame(frames, event("response.function_call_arguments.done", sequence++).put("item_id", item.path("id").asText())
                .put("output_index", 0).put("arguments", item.path("arguments").asText()));
        }
        frame(frames, event("response.output_item.done", sequence++).put("output_index", 0).set("item", item));
        frame(frames, event("response.completed", sequence).set("response", completed));
        return frames.toString();
    }

    private ObjectNode event(String type, int sequence) { return json.createObjectNode().put("type", type).put("sequence_number", sequence); }
    private ObjectNode contentEvent(String type, int sequence, JsonNode item) {
        return event(type, sequence).put("item_id", item.path("id").asText()).put("output_index", 0).put("content_index", 0);
    }
    private void frame(StringBuilder target, JsonNode event) { target.append("event: ").append(event.path("type").asText()).append("\ndata: ").append(event).append("\n\n"); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
