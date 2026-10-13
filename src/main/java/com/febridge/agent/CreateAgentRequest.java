package com.febridge.agent;

import jakarta.validation.constraints.*;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CreateAgentRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 1000) String description,
    @NotBlank @Size(max = 100000) String systemPrompt,
    JsonNode modelConfig,
    UUID modelId
) {
    public CreateAgentRequest(
        String name,
        String description,
        String systemPrompt,
        JsonNode modelConfig
    ) {
        this(name, description, systemPrompt, modelConfig, null);
    }

    @AssertTrue(message = "modelConfig must be a JSON object")
    public boolean isModelConfigValid() {
        return modelConfig == null || modelConfig.isObject();
    }
}
