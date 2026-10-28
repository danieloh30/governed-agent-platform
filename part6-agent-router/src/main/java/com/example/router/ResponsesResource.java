package com.example.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/responses")
@Consumes(MediaType.APPLICATION_JSON)
public class ResponsesResource {
    @Inject ResponsesService service;

    @POST
    public Response create(JsonNode request) {
        var result = service.respond(request);
        return Response.status(result.status()).type(result.stream() ? "text/event-stream" : MediaType.APPLICATION_JSON)
            .entity(result.stream() ? service.events((ObjectNode) result.body()) : result.body()).build();
    }
}
