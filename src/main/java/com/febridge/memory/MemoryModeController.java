package com.febridge.memory;

import com.febridge.agent.AgentService;
import com.febridge.user.AuthController;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/{agentId}/memory-mode")
public class MemoryModeController {

    private final LongMemoryStore store;
    private final AgentService agents;

    public MemoryModeController(LongMemoryStore store, AgentService agents) {
        this.store = store;
        this.agents = agents;
    }

    @GetMapping
    public Map<String, MemoryMode> get(@PathVariable UUID agentId) {
        agents.findById(agentId);
        return Map.of("mode", store.mode(AuthController.current().id(), agentId));
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void set(
        @PathVariable UUID agentId,
        @Valid @RequestBody LongMemoryDtos.ModeInput input
    ) {
        agents.findById(agentId);
        store.setMode(AuthController.current().id(), agentId, input.mode());
    }
}
