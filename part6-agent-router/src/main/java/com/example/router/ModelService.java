package com.example.router;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import static com.example.router.ModelApi.*;

@ApplicationScoped
public class ModelService {
    @ConfigProperty(name = "model.backend-name")
    String backend;

    private String mode = "healthy";
    private long requests;
    private String lastModel;

    public synchronized State state() {
        return new State(backend, mode, requests, lastModel);
    }

    public synchronized State control(Control control) {
        mode = control.mode();
        if (control.reset()) {
            requests = 0;
            lastModel = null;
        }
        return state();
    }

    public synchronized Result complete(CompletionRequest request) {
        requests++;
        lastModel = request.model();
        if (!mode.equals("healthy")) {
            int status = mode.equals("unavailable") ? 503 : 400;
            return new Result(status, new ErrorResponse(new ErrorDetail(
                    backend + ": simulated " + mode, "lab_error", Integer.toString(status))));
        }
        return new Result(200, new Completion("chatcmpl-" + backend + "-" + requests,
                "chat.completion", 0, request.model(),
                List.of(new Choice(0, new Message("assistant",
                        backend + ": Acme support request received (stub; no inference)."), "stop")),
                new Usage(12, 8, 20)));
    }
}
