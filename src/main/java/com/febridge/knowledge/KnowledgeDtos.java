package com.febridge.knowledge;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class KnowledgeDtos {

    private KnowledgeDtos() {}

    public record BaseInput(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Size(max = 1000) String description,
        @NotNull @Pattern(regexp = "PRIVATE|SHARED") String visibility
    ) {}

    public record DocumentInput(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200000) String content
    ) {}

    public record Query(@NotBlank @Size(max = 500) String query, @Min(1) @Max(10) int limit) {}

    public record Binding(@NotNull @Size(max = 8) List<@NotNull UUID> knowledgeBaseIds) {}

    public record Hit(
        String citation,
        UUID knowledgeBaseId,
        String knowledgeBaseName,
        UUID documentId,
        String documentName,
        UUID chunkId,
        int ordinal,
        String content,
        double score
    ) {}
}
