package com.febridge.memory;

import com.febridge.agent.AgentService;
import com.febridge.user.AuthController;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/{agentId}/long-memory")
public class LongMemoryController {

    private final LongMemoryStore store;
    private final AgentService agents;

    public LongMemoryController(LongMemoryStore store, AgentService agents) {
        this.store = store;
        this.agents = agents;
    }

    @GetMapping
    public Map<String, Object> view(@PathVariable UUID agentId) {
        agents.findById(agentId);
        return store.view(AuthController.current().id(), agentId);
    }

    @PutMapping("/policy")
    @org.springframework.web.bind.annotation.ResponseStatus(
        org.springframework.http.HttpStatus.NO_CONTENT
    )
    public void policy(@PathVariable UUID agentId, @RequestBody LongMemoryDtos.Policy input) {
        agents.findById(agentId);
        store.policy(AuthController.current().id(), agentId, input.enabled());
    }

    @PutMapping("/mode")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void mode(
        @PathVariable UUID agentId,
        @Valid @RequestBody LongMemoryDtos.ModeInput input
    ) {
        agents.findById(agentId);
        store.setMode(AuthController.current().id(), agentId, input.mode());
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(
        org.springframework.http.HttpStatus.NO_CONTENT
    )
    public void create(@PathVariable UUID agentId, @Valid @RequestBody LongMemoryDtos.Input input) {
        agents.findById(agentId);
        store.create(AuthController.current().id(), agentId, input);
    }

    @PutMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(
        org.springframework.http.HttpStatus.NO_CONTENT
    )
    public void edit(
        @PathVariable UUID agentId,
        @PathVariable UUID id,
        @Valid @RequestBody LongMemoryDtos.Edit input
    ) {
        agents.findById(agentId);
        store.edit(AuthController.current().id(), agentId, id, input, false);
    }

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(
        org.springframework.http.HttpStatus.NO_CONTENT
    )
    public void delete(
        @PathVariable UUID agentId,
        @PathVariable UUID id,
        @Valid @RequestBody LongMemoryDtos.Edit input
    ) {
        agents.findById(agentId);
        store.edit(AuthController.current().id(), agentId, id, input, true);
    }
}
