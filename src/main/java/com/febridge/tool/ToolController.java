package com.febridge.tool;

import static com.febridge.tool.ToolDtos.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ToolController {

    private final ToolRegistry registry;
    private final com.febridge.trace.RunTrace trace;

    public ToolController(ToolRegistry registry, com.febridge.trace.RunTrace trace) {
        this.registry = registry;
        this.trace = trace;
    }

    @GetMapping("/mcp/servers")
    public List<ServerView> servers() {
        return registry.servers();
    }

    @PostMapping("/mcp/servers")
    public ServerView create(@Valid @RequestBody ServerInput input) {
        return registry.save(null, input);
    }

    @PutMapping("/mcp/servers/{id}")
    public ServerView update(@PathVariable UUID id, @Valid @RequestBody ServerInput input) {
        return registry.save(id, input);
    }

    @PostMapping("/mcp/servers/{id}/discover")
    public List<ToolView> discover(@PathVariable UUID id) {
        return registry.sync(id);
    }

    @GetMapping("/mcp/tools")
    public List<ToolView> tools() {
        return registry.tools();
    }

    @PutMapping("/mcp/tools/{id}/policy")
    public ToolView policy(@PathVariable UUID id, @RequestBody ToolPolicy input) {
        return registry.updatePolicy(id, input);
    }

    @GetMapping("/agents/{id}/tools")
    public List<ToolView> grants(@PathVariable UUID id) {
        return registry.grants(id);
    }

    @GetMapping("/agents/{id}/mcp-servers")
    public List<ServerView> boundServers(@PathVariable UUID id) {
        return registry.boundServers(id);
    }

    @PutMapping("/agents/{id}/mcp-servers")
    public List<ServerView> bindServers(
        @PathVariable UUID id,
        @Valid @RequestBody ServerBinding input
    ) {
        return registry.bindServers(id, input);
    }

    @GetMapping("/agents/{id}/tool-audits")
    public List<AuditView> audits(@PathVariable UUID id, @RequestParam UUID requestId) {
        trace.requireAccess(id, requestId);
        return registry.audits(id, requestId);
    }
}
