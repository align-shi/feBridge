package com.febridge.conversation;

import com.febridge.chat.ChatDtos.Answer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ConversationDtos {

    private ConversationDtos() {}

    public record Session(
        UUID id,
        UUID agentId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean generating
    ) {}

    public record SendRequest(
        @NotNull UUID requestId,
        @NotBlank @Size(max = 32000) String message
    ) {}

    public record Message(
        String id,
        UUID requestId,
        String role,
        String content,
        String status,
        String error,
        OffsetDateTime createdAt,
        Answer answer
    ) {}

    public record History(Session session, List<Message> messages) {}

    public record TurnResult(UUID requestId, Answer answer) {}
}
