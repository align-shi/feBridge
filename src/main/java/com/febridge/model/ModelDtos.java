package com.febridge.model;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class ModelDtos {

    private ModelDtos() {}

    public record ProviderInput(
        @NotBlank @Size(max = 100) String name,
        @NotNull ProviderType providerType,
        @Size(max = 4096) String apiKey,
        boolean enabled
    ) {
        @Override
        public String toString() {
            return "ProviderInput[apiKey=REDACTED]";
        }
    }

    public record ProviderView(
        UUID id,
        String name,
        ProviderType providerType,
        String baseUrl,
        boolean keyConfigured,
        boolean enabled,
        OffsetDateTime createdAt
    ) {}

    public record Preset(ProviderType providerType, String baseUrl, String suggestedModel) {}

    public record ModelInput(
        @NotNull UUID providerId,
        @NotBlank @Size(max = 100) String modelCode,
        @NotBlank @Size(max = 100) String displayName,
        boolean supportsTools,
        JsonNode defaultParameters,
        boolean enabled
    ) {
        @AssertTrue(message = "defaultParameters must be a JSON object")
        public boolean isParametersValid() {
            return defaultParameters == null || defaultParameters.isObject();
        }
    }

    public record ModelView(
        UUID id,
        UUID providerId,
        String modelCode,
        String displayName,
        boolean supportsTools,
        JsonNode defaultParameters,
        boolean enabled,
        OffsetDateTime createdAt
    ) {}

    public record Binding(@NotNull UUID modelId, JsonNode parameters) {
        @AssertTrue(message = "parameters must be a JSON object")
        public boolean isParametersValid() {
            return parameters == null || parameters.isObject();
        }
    }
}
