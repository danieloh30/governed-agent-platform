package com.example.eval;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalSuite(
        String name,
        String description,
        List<EvalCase> cases,
        List<WorkflowCase> workflows) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkflowCase(
            String id,
            String description,
            List<EvalCase> steps) {
    }
}
