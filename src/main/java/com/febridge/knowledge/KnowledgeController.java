package com.febridge.knowledge;

import static com.febridge.knowledge.KnowledgeDtos.*;

import com.febridge.agent.AgentService;
import com.febridge.user.AuthController;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final KnowledgeStore store;
    private final KnowledgeClients clients;
    private final KnowledgeSearch search;
    private final AgentService agents;

    public KnowledgeController(
        KnowledgeStore store,
        KnowledgeClients clients,
        KnowledgeSearch search,
        AgentService agents
    ) {
        this.store = store;
        this.clients = clients;
        this.search = search;
        this.agents = agents;
    }

    @GetMapping("/knowledge-bases")
    public List<Map<String, Object>> list() {
        return store.list(AuthController.current().id());
    }

    @PostMapping("/knowledge-bases")
    public Map<String, Object> create(@Valid @RequestBody BaseInput input) {
        return store.create(AuthController.current().id(), input);
    }

    @GetMapping("/knowledge-bases/{id}/documents")
    public List<Map<String, Object>> documents(@PathVariable UUID id) {
        return store.documents(AuthController.current().id(), id);
    }

    @PostMapping("/knowledge-bases/{id}/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> upload(
        @PathVariable UUID id,
        @Valid @RequestBody DocumentInput input
    ) {
        return Map.of(
            "id",
            store.upload(AuthController.current().id(), id, input, clients.space())
        );
    }

    @PostMapping("/knowledge-bases/{id}/documents/{document}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable UUID id, @PathVariable UUID document) {
        store.action(AuthController.current().id(), id, document, false);
    }

    @DeleteMapping("/knowledge-bases/{id}/documents/{document}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> delete(@PathVariable UUID id, @PathVariable UUID document) {
        store.action(AuthController.current().id(), id, document, true);
        return Map.of("status", "deleting");
    }

    @PostMapping("/knowledge-bases/{id}/search")
    public List<Hit> search(@PathVariable UUID id, @Valid @RequestBody Query input) {
        return search.search(
            AuthController.current().id(),
            List.of(id.toString()),
            input.query(),
            input.limit(),
            Duration.ofSeconds(25)
        );
    }

    @GetMapping("/agents/{id}/knowledge-bases")
    public List<Map<String, Object>> bound(@PathVariable UUID id) {
        agents.findById(id);
        return store.bound(AuthController.current().id(), id);
    }

    @PutMapping("/agents/{id}/knowledge-bases")
    public List<Map<String, Object>> bind(
        @PathVariable UUID id,
        @Valid @RequestBody Binding input
    ) {
        agents.findById(id);
        return store.bind(AuthController.current().id(), id, input.knowledgeBaseIds());
    }
}
