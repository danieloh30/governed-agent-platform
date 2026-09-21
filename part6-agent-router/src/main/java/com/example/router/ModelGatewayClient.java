package com.example.router;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "model-gateway")
@Path("/v1/chat/completions")
@Consumes(MediaType.APPLICATION_JSON)
public interface ModelGatewayClient {
    @POST
    Response complete(ModelApi.CompletionRequest request);
}
