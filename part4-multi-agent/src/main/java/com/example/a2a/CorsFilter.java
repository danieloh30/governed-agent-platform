package com.example.a2a;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class CorsFilter {

    public void registerCors(@Observes Filters filters) {
        filters.register(rc -> {
            HttpServerResponse response = rc.response();
            response.putHeader("Access-Control-Allow-Origin", "*");
            response.putHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.putHeader("Access-Control-Allow-Headers", "Content-Type, Accept, A2A-Version");

            if (rc.request().method() == HttpMethod.OPTIONS) {
                response.setStatusCode(204).end();
                return;
            }
            rc.next();
        }, 100);
    }
}
