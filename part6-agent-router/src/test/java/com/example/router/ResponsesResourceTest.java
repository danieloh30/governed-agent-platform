package com.example.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ResponsesResourceTest {
    @Inject ObjectMapper json;
    @BeforeEach void reset() {
        given().contentType("application/json").body(new ModelApi.Control("healthy", true)).post("/admin").then().statusCode(200);
    }

    @Test void textResponseUsesItemsAndExplicitStatelessSemantics() {
        given().contentType("application/json").body(ResponsesExamples.text("acme-model-v1", false)).post("/v1/responses")
            .then().statusCode(200).contentType("application/json").body("object", equalTo("response"))
            .body("status", equalTo("completed")).body("store", equalTo(false))
            .body("output[0].type", equalTo("message")).body("output[0].content[0].type", equalTo("output_text"))
            .body("output[0].content[0].text", startsWith("primary:")).body("usage.total_tokens", equalTo(20));
    }

    @Test void toolResultMustMatchThePreviousCallAndCanBeReplayedStatelessly() throws Exception {
        ObjectNode request = ResponsesExamples.tool("acme-model-v1");
        var response = given().contentType("application/json").body(request).post("/v1/responses").then().statusCode(200).extract().asString();
        var call = json.readTree(response).path("output").get(0);
        assertEquals("function_call", call.path("type").asText());
        ObjectNode next = ResponsesExamples.continueWithToolResult(request, call);
        assertEquals(request.path("input").asText(), next.path("input").get(0).path("content").asText());
        given().contentType("application/json").body(next).post("/v1/responses").then().statusCode(200)
            .body("output[0].content[0].text", containsString("CUST-4091 is ACTIVE"));
        ((ObjectNode) next.path("input").get(2)).put("call_id", "wrong-call");
        given().contentType("application/json").body(next).post("/v1/responses").then().statusCode(400);
        given().get("/admin").then().body("requests", equalTo(2));
    }

    @Test void streamingEventsAreOrderedAndHaveATerminalResponse() throws Exception {
        String stream = given().contentType("application/json").body(ResponsesExamples.text("m", true)).post("/v1/responses")
            .then().statusCode(200).contentType("text/event-stream").extract().asString();
        int expected = 0; String last = "";
        for (String frame : stream.split("\n\n")) {
            String[] lines = frame.split("\n");
            var event = json.readTree(lines[1].substring(6));
            assertEquals("event: " + event.path("type").asText(), lines[0]);
            assertEquals(expected++, event.path("sequence_number").asInt());
            last = event.path("type").asText();
        }
        assertEquals("response.completed", last);
        assertTrue(stream.contains("response.output_text.delta"));
    }

    @Test void unsupportedFeaturesFailBeforeCountingAttempts() {
        for (String body : new String[] {"null", "{}", "{\"model\":\"m\",\"input\":[]}",
                "{\"model\":\"m\",\"input\":\"x\",\"store\":true}",
                "{\"model\":\"m\",\"input\":\"x\",\"previous_response_id\":\"resp_old\"}",
                "{\"model\":\"m\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_image\"}]}]}"}) {
            given().contentType("application/json").body(body).post("/v1/responses").then().statusCode(400);
        }
        given().get("/admin").then().body("requests", equalTo(0));
    }

    @Test void responsesUseTheExistingFailureModesAndAttemptCounter() {
        given().contentType("application/json").body(new ModelApi.Control("unavailable", true)).post("/admin");
        given().contentType("application/json").body(ResponsesExamples.text("m", true)).post("/v1/responses")
            .then().statusCode(503).contentType("application/json");
        given().get("/admin").then().body("requests", equalTo(1));
    }

    @Test void localToolDispatcherRejectsModelSuppliedUnknownOperations() {
        ObjectNode call = json.createObjectNode().put("type", "function_call").put("call_id", "call_bad")
            .put("name", "process_refund").put("arguments", "{}");
        assertThrows(IllegalArgumentException.class, () -> ResponsesExamples.continueWithToolResult(ResponsesExamples.tool("m"), call));
    }
}
