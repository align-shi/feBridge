package com.febridge.agent;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AgentDefinition> create(@Valid @RequestBody CreateAgentRequest request) {
        var agent = service.create(request);
        return ResponseEntity.created(URI.create("/api/agents/" + agent.id())).body(agent);
    }

    @GetMapping("/{id}")
    public AgentDefinition findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    public List<AgentDefinition> findRecent() {
        return service.findRecent();
    }

    @PutMapping("/{id}/model")
    public AgentDefinition bind(
        @PathVariable UUID id,
        @Valid @RequestBody com.febridge.model.ModelDtos.Binding input
    ) {
        return service.bind(id, input);
    }
}
