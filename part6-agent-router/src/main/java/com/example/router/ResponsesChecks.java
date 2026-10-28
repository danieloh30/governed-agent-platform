package com.example.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class ResponsesChecks {
    @Inject ObjectMapper json;
    @RestClient ResponsesGatewayClient gateway;
    public record Report(List<String> passed, JsonNode functionCall, JsonNode continuation, String events) {}

    public Report run() {
        List<String> passed = new ArrayList<>();
        try (BackendAdminClient primary = admin(18081); BackendAdminClient fallback = admin(18082)) {
            try {
                reset(primary, fallback, "healthy");
                JsonNode text = create(ResponsesExamples.text("acme-support", false));
                check(text.path("output").get(0).path("content").get(0).path("text").asText().startsWith("primary:"), "Text did not come from primary");
                counts(primary, fallback, 1, 0);
                passed.add("Responses text uses output items through the primary route");

                reset(primary, fallback, "unavailable");
                JsonNode failedOver = create(ResponsesExamples.text("acme-support", false));
                check(failedOver.path("output").get(0).path("content").get(0).path("text").asText().startsWith("fallback:"), "Responses fallback failed");
                counts(primary, fallback, 1, 1);
                passed.add("Responses primary 503 falls back exactly once");

                reset(primary, fallback, "unavailable");
                ObjectNode request = ResponsesExamples.tool("acme-support");
                JsonNode call = create(request).path("output").get(0);
                counts(primary, fallback, 1, 1);
                primary.control(new ModelApi.Control("healthy", false));
                JsonNode continuation = create(ResponsesExamples.continueWithToolResult(request, call));
                String answer = continuation.path("output").get(0).path("content").get(0).path("text").asText();
                check(answer.startsWith("primary:") && answer.contains("CUST-4091 is ACTIVE"), "Full tool history was not portable after backend recovery");
                counts(primary, fallback, 2, 1);
                passed.add("Function call from fallback continues on recovered primary using full history and call_id");

                reset(primary, fallback, "healthy");
                String stream;
                try (Response response = gateway.create(ResponsesExamples.text("acme-support", true))) {
                    check(response.getStatus() == 200 && response.getMediaType().toString().startsWith("text/event-stream"), "Expected an SSE response");
                    stream = response.readEntity(String.class);
                }
                int sequence = 0; String terminal = ""; boolean delta = false;
                for (String frame : stream.replace("\r\n", "\n").split("\n\n")) {
                    String type = null, data = null;
                    for (String line : frame.split("\n")) {
                        if (line.startsWith("event:")) type = line.substring(6).trim();
                        if (line.startsWith("data:")) data = line.substring(5).trim();
                    }
                    if (data == null || data.equals("[DONE]")) continue;
                    JsonNode event = json.readTree(data);
                    check(event.path("type").asText().equals(type), "SSE event name and body type differ");
                    check(event.path("sequence_number").asInt(-1) == sequence++, "SSE sequence is not contiguous");
                    terminal = type;
                    delta |= "response.output_text.delta".equals(type);
                }
                check(delta && "response.completed".equals(terminal), "Missing text delta or terminal response");
                counts(primary, fallback, 1, 0);
                passed.add("SSE event names, sequence numbers, text delta, and terminal response survive the router");
                passed.forEach(name -> System.out.println("PASS " + name));
                return new Report(List.copyOf(passed), call, continuation, stream);
            } finally {
                try { primary.control(new ModelApi.Control("healthy", true)); }
                finally { fallback.control(new ModelApi.Control("healthy", true)); }
            }
        } catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid Responses JSON", invalid); }
    }

    private JsonNode create(ObjectNode request) throws java.io.IOException {
        try (Response response = gateway.create(request)) {
            String body = response.readEntity(String.class);
            check(response.getStatus() == 200, "Expected Responses HTTP 200, got " + response.getStatus() + ": " + body);
            JsonNode result = json.readTree(body);
            check("response".equals(result.path("object").asText()) && "completed".equals(result.path("status").asText()), "Invalid response envelope");
            return result;
        }
    }
    private BackendAdminClient admin(int port) {
        return QuarkusRestClientBuilder.newBuilder().baseUri(URI.create("http://127.0.0.1:" + port))
            .connectTimeout(1, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(BackendAdminClient.class);
    }
    private void reset(BackendAdminClient primary, BackendAdminClient fallback, String mode) {
        primary.control(new ModelApi.Control(mode, true)); fallback.control(new ModelApi.Control("healthy", true));
    }
    private void counts(BackendAdminClient primary, BackendAdminClient fallback, long p, long f) {
        var first = primary.state(); var second = fallback.state();
        check(first.requests() == p && second.requests() == f, "Unexpected Responses backend attempt counts");
        for (var state : List.of(first, second)) if (state.requests() > 0) check("acme-model-v1".equals(state.lastModel()), "Responses model mapping was not applied");
    }
    private static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
}
