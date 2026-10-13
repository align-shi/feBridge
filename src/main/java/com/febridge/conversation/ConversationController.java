package com.febridge.conversation;

import static com.febridge.conversation.ConversationDtos.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/{agentId}/conversations")
public class ConversationController {

    private final ConversationStore store;
    private final ConversationService service;

    public ConversationController(ConversationStore store, ConversationService service) {
        this.store = store;
        this.service = service;
    }

    @GetMapping
    public List<Session> list(@PathVariable UUID agentId) {
        return store.list(agentId);
    }

    @PostMapping
    public Session create(@PathVariable UUID agentId) {
        return store.create(agentId);
    }

    @GetMapping("/{sessionId}/messages")
    public History history(@PathVariable UUID agentId, @PathVariable UUID sessionId) {
        return store.history(agentId, sessionId);
    }

    @PostMapping("/{sessionId}/messages")
    public TurnResult send(
        @PathVariable UUID agentId,
        @PathVariable UUID sessionId,
        @Valid @RequestBody SendRequest request
    ) {
        return service.send(agentId, sessionId, request);
    }
}
