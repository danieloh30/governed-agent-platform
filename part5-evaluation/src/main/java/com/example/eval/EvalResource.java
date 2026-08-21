package com.example.eval;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/eval")
@Produces(MediaType.APPLICATION_JSON)
public class EvalResource {

    @Inject
    EvalRunner runner;

    @GET
    @Path("/suites")
    public List<String> listSuites() {
        return runner.listSuites();
    }

    @GET
    @Path("/suites/{name}")
    public EvalSuite getSuite(@PathParam("name") String name) throws Exception {
        return runner.loadSuite(name);
    }

    @POST
    @Path("/run/{suite}")
    public EvalReport runSuite(@PathParam("suite") String suite) throws Exception {
        return runner.runSuite(suite);
    }
}
