package com.febridge.tool;

import com.febridge.mcp.McpAuthentication;
import com.febridge.mcp.McpAuthentication.Type;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class ToolDtos {

    private ToolDtos() {}

    public record ServerInput(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 500) String endpoint,
        @Size(max = 4096) String token,
        boolean enabled,
        @Min(1) @Max(30) int timeoutSeconds,
        @Min(1024) @Max(32768) int resultLimit,
        Type authType,
        @Size(max = 100) String authHeader
    ) {
        public ServerInput(
            String name,
            String endpoint,
            String token,
            boolean enabled,
            int timeoutSeconds,
            int resultLimit
        ) {
            this(name, endpoint, token, enabled, timeoutSeconds, resultLimit, Type.BEARER, null);
        }

        @Override
        public String toString() {
            return "ServerInput[token=REDACTED]";
        }
    }

    public record ServerView(
        UUID id,
        String name,
        String endpoint,
        boolean tokenConfigured,
        boolean enabled,
        int timeoutSeconds,
        int resultLimit,
        Type authType,
        String authHeader
    ) {}

    public record ToolView(
        UUID id,
        UUID serverId,
        String serverName,
        String name,
        String description,
        JsonNode inputSchema,
        boolean readOnly,
        boolean enabled
    ) {
        public String alias() {
            return "tool_" + id.toString().replace("-", "");
        }
    }

    public record ToolPolicy(boolean enabled) {
        public ToolPolicy(boolean ignoredReadOnly, boolean enabled) {
            this(enabled);
        }
    }

    public record ServerBinding(@NotNull @Size(max = 32) List<@NotNull UUID> serverIds) {}

    public record AuditView(
        UUID id,
        UUID requestId,
        String callId,
        String toolName,
        String status,
        JsonNode argumentFields,
        int resultSize,
        String errorCode,
        long durationMillis
    ) {}

    public record ToolResult(String content, boolean error) {}
}
