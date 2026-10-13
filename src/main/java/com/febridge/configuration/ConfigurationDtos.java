package com.febridge.configuration;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class ConfigurationDtos {

    private ConfigurationDtos() {}

    public record Profile(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 100000) String systemPrompt
    ) {}

    public record SkillInput(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 8000) String instructions,
        boolean enabled
    ) {}

    public record Skill(
        UUID id,
        String name,
        String description,
        String instructions,
        boolean enabled
    ) {}

    public enum FailurePolicy {
        CONTINUE,
        STOP,
    }

    public record Rules(
        @Min(1) @Max(10) int maxModelCalls,
        @Min(0) @Max(20) int maxToolCalls,
        @Min(10) @Max(170) int taskTimeoutSeconds,
        @Min(1) @Max(30) int toolTimeoutSeconds,
        boolean toolsEnabled,
        @NotNull FailurePolicy failurePolicy
    ) {
        public static Rules defaults() {
            return new Rules(6, 12, 170, 30, true, FailurePolicy.CONTINUE);
        }
    }

    public record SkillBinding(@NotNull @Size(max = 8) List<@NotNull UUID> skillIds) {}

    public record RuntimeConfig(String systemPrompt, Rules rules, List<Skill> skills) {}
}
