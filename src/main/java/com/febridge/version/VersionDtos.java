package com.febridge.version;

import com.febridge.configuration.ConfigurationDtos.Rules;
import com.febridge.memory.MemoryMode;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class VersionDtos {

    private VersionDtos() {}

    public record Snapshot(
        int schemaVersion,
        String name,
        String description,
        String systemPrompt,
        UUID modelId,
        JsonNode parameters,
        Rules rules,
        List<UUID> skillIds,
        List<UUID> mcpServerIds,
        List<UUID> knowledgeBaseIds,
        MemoryMode memoryMode
    ) {}

    public record Publish(
        @NotNull @Size(max = 1000) String note,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedFingerprint
    ) {}

    public record Expected(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedFingerprint
    ) {}

    public record Version(
        UUID id,
        int number,
        String note,
        String fingerprint,
        UUID publishedBy,
        String createdAt
    ) {}

    public record State(
        Snapshot draft,
        String fingerprint,
        UUID activeVersionId,
        boolean changed,
        List<Version> versions
    ) {}
}
