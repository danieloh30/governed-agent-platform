package com.example.router;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import static com.example.router.ModelApi.*;

@Path("/")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ModelResource {
    @Inject
    ModelService service;

    @POST
    @Path("v1/chat/completions")
    public Response complete(@NotNull @Valid CompletionRequest request) {
        Result result = service.complete(request);
        return Response.status(result.status()).entity(result.body()).build();
    }

    @GET
    @Path("admin")
    public State state() {
        return service.state();
    }

    @POST
    @Path("admin")
    public State control(@NotNull @Valid Control control) {
        return service.control(control);
    }
}
