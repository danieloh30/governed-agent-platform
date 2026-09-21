package com.example.router;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/admin")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface BackendAdminClient extends AutoCloseable {
    @GET
    ModelApi.State state();

    @POST
    ModelApi.State control(ModelApi.Control control);

    @Override
    void close();
}
