package com.example.router;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import static com.example.router.ModelApi.*;

/** Same-origin browser facade. All model traffic still goes through the real Agent Router. */
@Path("/lab")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ConsoleResource {
    @RestClient ModelGatewayClient gateway;
    @Inject RoutingChecks checks;

    public record BackendView(boolean available, State state, String error) {}
    public record Snapshot(boolean gatewayReady, BackendView primary, BackendView fallback) {}
    public record Verification(List<String> passed) {}

    @GET
    @Path("state")
    public synchronized Snapshot state() {
        boolean ready;
        try (HealthClient client = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:1064/health"))
                .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS)
                .build(HealthClient.class); Response response = client.get()) {
            ready = response.getStatus() == 200;
        } catch (RuntimeException error) { ready = false; }
        return new Snapshot(ready, view("primary"), view("fallback"));
    }

    @POST
    @Path("request")
    public synchronized Response request(@NotNull @Valid CompletionRequest request) {
        try (Response response = gateway.complete(request)) {
            return Response.status(response.getStatus()).type(MediaType.APPLICATION_JSON)
                    .entity(response.readEntity(String.class)).build();
        } catch (RuntimeException error) { throw unavailable("Agent Router is unreachable. Start ./start-all.sh."); }
    }

    @POST
    @Path("backends/{backend}")
    public synchronized State control(@PathParam("backend") String backend, @NotNull @Valid Control control) {
        try (BackendAdminClient client = admin(backend)) { return client.control(control); }
        catch (NotFoundException error) { throw error; }
        catch (RuntimeException error) { throw unavailable(backend + " backend is unreachable."); }
    }

    @POST
    @Path("reset")
    public synchronized Snapshot reset() {
        control("primary", new Control("healthy", true));
        control("fallback", new Control("healthy", true));
        return state();
    }

    @POST
    @Path("verify")
    public synchronized Verification verify() {
        try { return new Verification(checks.run()); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable("Verification interrupted.");
        } catch (RuntimeException error) { throw unavailable("Verification failed: " + error.getMessage()); }
    }

    private BackendView view(String backend) {
        try (BackendAdminClient client = admin(backend)) { return new BackendView(true, client.state(), null); }
        catch (RuntimeException error) { return new BackendView(false, null, backend + " backend is unreachable"); }
    }

    private BackendAdminClient admin(String backend) {
        int port = switch (backend) {
            case "primary" -> 18081;
            case "fallback" -> 18082;
            default -> throw new NotFoundException();
        };
        return QuarkusRestClientBuilder.newBuilder().baseUri(URI.create("http://127.0.0.1:" + port))
                .connectTimeout(1, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build(BackendAdminClient.class);
    }

    private WebApplicationException unavailable(String message) {
        return new WebApplicationException(Response.status(502)
                .entity(new ErrorResponse(new ErrorDetail(message, "lab_connection_error", "502"))).build());
    }
}
