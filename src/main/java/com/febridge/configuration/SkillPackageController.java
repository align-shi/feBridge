package com.febridge.configuration;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/skills")
public class SkillPackageController {

    private final SkillPackages packages;

    public SkillPackageController(SkillPackages packages) {
        this.packages = packages;
    }

    @GetMapping("/{id}/resources")
    public List<SkillPackages.FileView> resources(@PathVariable UUID id) {
        return packages.resources(id);
    }

    @GetMapping("/{id}/folders")
    public List<String> folders(@PathVariable UUID id) {
        return packages.folders(id);
    }

    @PostMapping("/packages")
    public ConfigurationDtos.Skill create(@Valid @RequestBody SkillPackages.PackageInput input) {
        return packages.save(null, input);
    }

    @PutMapping("/{id}/package")
    public ConfigurationDtos.Skill update(
        @PathVariable UUID id,
        @Valid @RequestBody SkillPackages.PackageInput input
    ) {
        return packages.save(id, input);
    }
}
