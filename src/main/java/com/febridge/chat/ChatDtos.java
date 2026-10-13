package com.febridge.chat;

import jakarta.validation.constraints.*;

public final class ChatDtos {

    private ChatDtos() {}

    public record Request(@NotBlank @Size(max = 32000) String message) {}

    public record Usage(Long promptTokens, Long completionTokens, Long totalTokens) {}

    public record Answer(
        String content,
        String model,
        String provider,
        Usage usage,
        long durationMillis
    ) {}
}
