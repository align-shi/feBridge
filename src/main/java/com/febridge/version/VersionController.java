package com.febridge.version;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/{id}/versions")
public class VersionController {

    private final AgentVersions service;

    public VersionController(AgentVersions service) {
        this.service = service;
    }

    @GetMapping
    public VersionDtos.State state(@PathVariable UUID id) {
        return service.state(id);
    }

    @PostMapping
    public VersionDtos.State publish(
        @PathVariable UUID id,
        @Valid @RequestBody VersionDtos.Publish input
    ) {
        return service.publish(id, input);
    }

    @GetMapping("/{version}")
    public Map<String, Object> detail(@PathVariable UUID id, @PathVariable UUID version) {
        return service.detail(id, version);
    }

    @PostMapping("/{version}/activate")
    public VersionDtos.State activate(@PathVariable UUID id, @PathVariable UUID version) {
        return service.activate(id, version);
    }

    @PostMapping("/{version}/restore")
    public VersionDtos.State restore(
        @PathVariable UUID id,
        @PathVariable UUID version,
        @Valid @RequestBody VersionDtos.Expected input
    ) {
        return service.restore(id, version, input);
    }
}
