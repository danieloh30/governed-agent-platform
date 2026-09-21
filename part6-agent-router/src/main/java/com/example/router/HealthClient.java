package com.example.router;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.core.Response;

/** Base URI is the complete readiness endpoint selected by the launcher. */
public interface HealthClient extends AutoCloseable {
    @GET
    Response get();

    @Override
    void close();
}
