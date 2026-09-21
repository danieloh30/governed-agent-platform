package com.example.router;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/** Minimal, non-streaming OpenAI-shaped contract for deterministic transport exercises. */
public final class ModelApi {
    private ModelApi() {}

    public record Message(@NotBlank String role, @NotBlank String content) {}

    public record CompletionRequest(@NotBlank String model,
            @NotEmpty List<@NotNull @Valid Message> messages,
            @AssertFalse(message = "The lab supports non-streaming requests only") Boolean stream) {}

    public record Choice(int index, Message message, @JsonProperty("finish_reason") String finishReason) {}

    public record Usage(@JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {}

    public record Completion(String id, String object, long created, String model,
            List<Choice> choices, Usage usage) {}

    public record Control(@NotNull @Pattern(regexp = "healthy|unavailable|bad-request") String mode,
            boolean reset) {}

    public record State(String backend, String mode, long requests,
            @JsonProperty("last_model") String lastModel) {}

    public record ErrorDetail(String message, String type, String code) {}
    public record ErrorResponse(ErrorDetail error) {}
    public record Result(int status, Object body) {}
}
