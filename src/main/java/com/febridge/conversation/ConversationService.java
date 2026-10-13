package com.febridge.conversation;

import static com.febridge.conversation.ConversationDtos.*;

import com.febridge.chat.AgentChatService;
import com.febridge.chat.OpenAiChatClient.PromptMessage;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationService {

    private final ConversationStore store;
    private final AgentChatService chat;

    public ConversationService(ConversationStore store, AgentChatService chat) {
        this.store = store;
        this.chat = chat;
    }

    public TurnResult send(UUID agentId, UUID sessionId, SendRequest request) {
        var begin = store.begin(agentId, sessionId, request);
        if (begin.existingAnswer() != null) {
            return new TurnResult(request.requestId(), begin.existingAnswer());
        }
        try {
            var messages = new ArrayList<>(begin.context());
            messages.add(new PromptMessage("user", request.message()));
            var answer = chat.chatMessages(agentId, messages, request.requestId());
            store.finish(agentId, sessionId, request.requestId(), answer);
            return new TurnResult(request.requestId(), answer);
        } catch (RuntimeException exception) {
            String reason = "生成失败，请稍后重试";
            if (exception instanceof ResponseStatusException status && status.getReason() != null) {
                reason = status.getReason();
            }
            store.fail(sessionId, request.requestId(), reason);
            throw exception;
        }
    }
}
