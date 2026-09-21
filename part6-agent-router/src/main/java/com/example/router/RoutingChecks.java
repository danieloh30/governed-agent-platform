package com.example.router;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import static com.example.router.ModelApi.*;

@ApplicationScoped
public class RoutingChecks {
    @RestClient
    ModelGatewayClient gateway;

    public List<String> run() throws InterruptedException {
        List<String> passed = new ArrayList<>();
        try (BackendAdminClient primary = admin(18081); BackendAdminClient fallback = admin(18082)) {
            try {
                reset(primary, fallback, "healthy", "healthy");
                // A listener can be ready just before Envoy has applied its routes.
                boolean ready = false;
                for (int i = 0; i < 30; i++) {
                    try (Response response = complete("acme-support")) {
                        if (response.getStatus() == 200) {
                            ready = true;
                            break;
                        }
                    }
                    Thread.sleep(500);
                }
                check(ready, "acme-support route did not become ready; restore config.yaml");

                reset(primary, fallback, "healthy", "healthy");
                answeredBy("primary");
                counts(primary, fallback, 1, 0);
                pass(passed, "primary routing and model-name mapping");

                reset(primary, fallback, "healthy", "healthy");
                status("unconfigured-model", 404);
                counts(primary, fallback, 0, 0);
                pass(passed, "unmatched model never reaches either backend");

                reset(primary, fallback, "unavailable", "healthy");
                answeredBy("fallback");
                counts(primary, fallback, 1, 1);
                pass(passed, "primary 503 falls back exactly once");

                reset(primary, fallback, "bad-request", "healthy");
                status("acme-support", 400);
                counts(primary, fallback, 1, 0);
                pass(passed, "primary 400 is not retried");

                reset(primary, fallback, "unavailable", "unavailable");
                status("acme-support", 503);
                counts(primary, fallback, 1, 1);
                pass(passed, "both unavailable returns 503 with bounded attempts");

                reset(primary, fallback, "healthy", "healthy");
                answeredBy("primary");
                counts(primary, fallback, 1, 0);
                pass(passed, "recovery returns to primary");
                System.out.println("6/6 routing checks passed; these checks do not evaluate model quality.");
            } finally {
                // Attempt both resets even if one backend has become unreachable.
                try {
                    primary.control(new Control("healthy", true));
                } finally {
                    fallback.control(new Control("healthy", true));
                }
            }
        }
        return List.copyOf(passed);
    }

    private static void pass(List<String> passed, String name) {
        passed.add(name);
        System.out.println("PASS " + name);
    }

    private BackendAdminClient admin(int port) {
        return QuarkusRestClientBuilder.newBuilder().baseUri(URI.create("http://127.0.0.1:" + port))
                .connectTimeout(1, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
                .build(BackendAdminClient.class);
    }

    private void reset(BackendAdminClient primary, BackendAdminClient fallback, String p, String f) {
        primary.control(new Control(p, true));
        fallback.control(new Control(f, true));
    }

    private Response complete(String model) {
        return gateway.complete(new CompletionRequest(model,
                List.of(new Message("user", "Summarize Acme support status.")), false));
    }

    private void status(String model, int expected) {
        try (Response response = complete(model)) {
            check(response.getStatus() == expected,
                    "Expected HTTP " + expected + "; got " + response.getStatus() + ": " + response.readEntity(String.class));
        }
    }

    private void answeredBy(String backend) {
        try (Response response = complete("acme-support")) {
            check(response.getStatus() == 200, "Expected 200 from " + backend + "; got " + response.getStatus());
            Completion body = response.readEntity(Completion.class);
            check(body.choices().getFirst().message().content().startsWith(backend + ":"),
                    "Wrong backend completion: " + body);
        }
    }

    private void counts(BackendAdminClient primary, BackendAdminClient fallback, long p, long f) {
        List<State> states = List.of(primary.state(), fallback.state());
        check(states.get(0).requests() == p && states.get(1).requests() == f,
                "Expected attempts [" + p + ", " + f + "], got " + states);
        for (State state : states) {
            if (state.requests() > 0) {
                check("acme-model-v1".equals(state.lastModel()), "Wrong upstream model: " + state);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
