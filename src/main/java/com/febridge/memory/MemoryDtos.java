package com.febridge.memory;

import com.febridge.chat.ChatDtos.Usage;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class MemoryDtos {

    private MemoryDtos() {}

    public record View(
        JsonNode content,
        String coveredThroughSequence,
        long coveredTurns,
        int version,
        boolean inputTruncated,
        String model,
        Usage usage,
        Long durationMillis,
        String updatedAt,
        long pendingTurns
    ) {}

    public record Plan(View previous, List<Map<String, Object>> candidates, long pendingTurns) {}

    public record Prepared(JsonNode content, Usage usage) {}
}
