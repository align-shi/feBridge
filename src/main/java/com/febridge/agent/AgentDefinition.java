package com.febridge.agent;

import java.time.OffsetDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record AgentDefinition(
    UUID id,
    String name,
    String description,
    String systemPrompt,
    JsonNode modelConfig,
    OffsetDateTime createdAt,
    UUID modelId
) {
    public AgentDefinition(
        UUID id,
        String name,
        String description,
        String systemPrompt,
        JsonNode modelConfig,
        OffsetDateTime createdAt
    ) {
        this(id, name, description, systemPrompt, modelConfig, createdAt, null);
    }
}
