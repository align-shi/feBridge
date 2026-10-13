package com.febridge.chat;

import static com.febridge.chat.ChatDtos.*;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/{id}/chat")
public class AgentChatController {

    private final AgentChatService service;

    public AgentChatController(AgentChatService service) {
        this.service = service;
    }

    @PostMapping
    public Answer chat(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return service.chat(id, request);
    }
}
