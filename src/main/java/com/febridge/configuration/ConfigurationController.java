package com.febridge.configuration;

import static com.febridge.configuration.ConfigurationDtos.*;

import com.febridge.agent.AgentDefinition;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class ConfigurationController {

    private final AgentConfiguration configuration;

    public ConfigurationController(AgentConfiguration configuration) {
        this.configuration = configuration;
    }

    @PutMapping("/api/agents/{id}/profile")
    public AgentDefinition profile(@PathVariable UUID id, @Valid @RequestBody Profile input) {
        return configuration.profile(id, input);
    }

    @GetMapping("/api/skills")
    public List<Skill> skills() {
        return configuration.skills();
    }

    @PostMapping("/api/skills")
    public Skill create(@Valid @RequestBody SkillInput input) {
        return configuration.saveSkill(null, input);
    }

    @PutMapping("/api/skills/{id}")
    public Skill update(@PathVariable UUID id, @Valid @RequestBody SkillInput input) {
        return configuration.saveSkill(id, input);
    }

    @GetMapping("/api/agents/{id}/skills")
    public List<Skill> bound(@PathVariable UUID id) {
        return configuration.boundSkills(id);
    }

    @PutMapping("/api/agents/{id}/skills")
    public List<Skill> bind(@PathVariable UUID id, @Valid @RequestBody SkillBinding input) {
        return configuration.bind(id, input);
    }

    @GetMapping("/api/agents/{id}/execution-rules")
    public Rules rules(@PathVariable UUID id) {
        return configuration.rules(id);
    }

    @PutMapping("/api/agents/{id}/execution-rules")
    public Rules rules(@PathVariable UUID id, @Valid @RequestBody Rules input) {
        return configuration.saveRules(id, input);
    }
}
