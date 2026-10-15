package com.febridge.memory;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

public final class LongMemoryDtos {

    private LongMemoryDtos() {}

    public record Input(
        @NotBlank @Pattern(regexp = "preference|default|fact") String category,
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.-]{0,79}") String key,
        @NotBlank @Size(max = 500) String content,
        boolean enabled,
        OffsetDateTime expiresAt
    ) {}

    public record Edit(
        @NotBlank @Size(max = 500) String content,
        boolean enabled,
        OffsetDateTime expiresAt,
        @Min(1) int version
    ) {}

    public record Policy(boolean enabled) {}

    public record ModeInput(@NotNull MemoryMode mode) {}

    public record Candidate(String category, String key, String content, String evidence) {}
}
