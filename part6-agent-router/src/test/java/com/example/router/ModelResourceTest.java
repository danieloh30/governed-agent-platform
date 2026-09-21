package com.example.router;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ModelResourceTest {
    static final String COMPLETION = """
            {"model":"acme-model-v1","stream":false,"messages":[{"role":"user","content":"Status?"}]}
            """;

    @BeforeEach
    void reset() {
        control("healthy", true);
    }

    @Test
    void completionHasOpenAiShapeAndCountsOnlyModelAttempts() {
        given().contentType("application/json").body(COMPLETION).post("/v1/chat/completions")
                .then().statusCode(200).body("object", equalTo("chat.completion"))
                .body("model", equalTo("acme-model-v1"))
                .body("choices[0].message.content", startsWith("primary:"))
                .body("choices[0].finish_reason", equalTo("stop"))
                .body("usage.total_tokens", equalTo(20));
        for (int i = 0; i < 2; i++) {
            given().get("/admin").then().statusCode(200)
                    .body("requests", equalTo(1)).body("last_model", equalTo("acme-model-v1"));
        }
    }

    @Test
    void simulatedFailuresCountAttemptsAndRecoveryWorks() {
        for (String mode : new String[]{"unavailable", "bad-request"}) {
            control(mode, true);
            given().contentType("application/json").body(COMPLETION).post("/v1/chat/completions")
                    .then().statusCode(mode.equals("unavailable") ? 503 : 400)
                    .body("error.type", equalTo("lab_error"));
            given().get("/admin").then().body("requests", equalTo(1));
        }
        control("healthy", false);
        given().contentType("application/json").body(COMPLETION).post("/v1/chat/completions")
                .then().statusCode(200);
        given().get("/admin").then().body("requests", equalTo(2));
        control("healthy", true);
        given().get("/admin").then().body("requests", equalTo(0)).body("last_model", nullValue());
    }

    @Test
    void rejectsInvalidAndStreamingRequestsBeforeCounting() {
        for (String body : new String[]{"{}", "null", "{", "[]",
                "{\"model\":\"m\",\"messages\":[]}",
                "{\"model\":\"m\",\"messages\":[null]}",
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"\"}]}",
                COMPLETION.replace("false", "true")}) {
            given().contentType("application/json").body(body).post("/v1/chat/completions")
                    .then().statusCode(400);
        }
        given().get("/admin").then().body("requests", equalTo(0));
    }

    @Test
    void invalidControlCannotChangeBackendState() {
        control("unavailable", false);
        for (String body : new String[]{"{\"mode\":\"invalid\",\"reset\":true}", "{}", "null", "[]"}) {
            given().contentType("application/json").body(body).post("/admin").then().statusCode(400);
        }
        given().get("/admin").then().body("mode", equalTo("unavailable"));
    }

    @Test
    void oversizedBodyIsRejectedBeforeCounting() {
        given().contentType("application/json").body(COMPLETION.replace("Status?", "x".repeat(70000)))
                .post("/v1/chat/completions").then().statusCode(413);
        given().get("/admin").then().body("requests", equalTo(0));
    }

    @Test
    void readinessStaysUpDuringSimulatedProviderOutage() {
        control("unavailable", false);
        given().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void servesThePartSixConsole() {
        given().get("/").then().statusCode(200).contentType("text/html")
                .body(containsString("Model Routing Console"), containsString("Run All Six Checks"));
    }

    @Test
    void consoleRejectsInvalidRequestsAndBackendNames() {
        given().contentType("application/json").body("{}")
                .post("/lab/request").then().statusCode(400);
        given().contentType("application/json").body(new ModelApi.Control("healthy", true))
                .post("/lab/backends/unknown").then().statusCode(404);
        given().contentType("application/json").body("{\"mode\":\"invalid\"}")
                .post("/lab/backends/primary").then().statusCode(400);
    }

    private void control(String mode, boolean reset) {
        given().contentType("application/json").body(new ModelApi.Control(mode, reset))
                .post("/admin").then().statusCode(200);
    }
}
