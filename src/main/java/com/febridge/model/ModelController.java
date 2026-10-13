package com.febridge.model;

import static com.febridge.model.ModelDtos.*;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ModelController {

    private final ModelRegistry registry;
    private final ModelToolProbe probe;

    public ModelController(ModelRegistry registry, ModelToolProbe probe) {
        this.registry = registry;
        this.probe = probe;
    }

    @GetMapping("/model-providers/presets")
    public List<Preset> presets() {
        return registry.presets();
    }

    @GetMapping("/model-providers")
    public List<ProviderView> providers() {
        return registry.providers();
    }

    @PostMapping("/model-providers")
    public ResponseEntity<ProviderView> createProvider(@Valid @RequestBody ProviderInput input) {
        var value = registry.createProvider(input);
        return ResponseEntity.created(URI.create("/api/model-providers/" + value.id())).body(value);
    }

    @PutMapping("/model-providers/{id}")
    public ProviderView updateProvider(
        @PathVariable UUID id,
        @Valid @RequestBody ProviderInput input
    ) {
        return registry.updateProvider(id, input);
    }

    @GetMapping("/models")
    public List<ModelView> models() {
        return registry.models();
    }

    @PostMapping("/models")
    public ResponseEntity<ModelView> createModel(@Valid @RequestBody ModelInput input) {
        var value = registry.createModel(input);
        return ResponseEntity.created(URI.create("/api/models/" + value.id())).body(value);
    }

    @PutMapping("/models/{id}")
    public ModelView updateModel(@PathVariable UUID id, @Valid @RequestBody ModelInput input) {
        return registry.updateModel(id, input);
    }

    @PostMapping("/models/{id}/test-tool-calling")
    public ModelToolProbe.Outcome testTools(@PathVariable UUID id) {
        return probe.test(id);
    }
}
