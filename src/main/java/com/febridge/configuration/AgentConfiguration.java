package com.febridge.configuration;

import static com.febridge.configuration.ConfigurationDtos.*;

import com.febridge.agent.AgentDefinition;
import com.febridge.agent.AgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Validated
public class AgentConfiguration {

    private final AgentService agents;
    private final ConfigurationMapper mapper;
    private final JsonMapper json;

    public AgentConfiguration(AgentService agents, ConfigurationMapper mapper, JsonMapper json) {
        this.agents = agents;
        this.mapper = mapper;
        this.json = json;
    }

    @Transactional
    public AgentDefinition profile(UUID id, @NotNull @Valid Profile input) {
        agents.lock(id);
        var normalized = new Profile(
            input.name().strip(),
            input.description() == null ? "" : input.description(),
            input.systemPrompt()
        );
        mapper.profile(id, normalized);
        return agents.findById(id);
    }

    public List<Skill> skills() {
        return mapper.skills().stream().map(this::skillView).toList();
    }

    public Skill skill(UUID id) {
        var row = mapper.skill(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill 不存在");
        }
        return skillView(row);
    }

    @Transactional
    public Skill saveSkill(UUID id, @NotNull @Valid SkillInput input) {
        String existingCode = null;
        if (id == null) {
            id = UUID.randomUUID();
        } else {
            existingCode = skill(id).code();
        }
        var normalized = new SkillInput(
            input.name().strip(),
            input.description() == null ? "" : input.description(),
            input.instructions(),
            input.enabled(),
            input.code() != null
                ? input.code()
                : existingCode != null
                  ? existingCode
                  : "skill-" + id.toString().replace("-", "")
        );
        try {
            mapper.saveSkill(id, normalized);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill 编码已存在，请更换编码");
        }
        return skill(id);
    }

    public List<Skill> boundSkills(UUID agentId) {
        agents.findById(agentId);
        return mapper.boundSkills(agentId).stream().map(this::skillView).toList();
    }

    @Transactional
    public List<Skill> bind(UUID id, @NotNull @Valid SkillBinding input) {
        agents.lock(id);
        var ids = new LinkedHashSet<>(input.skillIds());
        if (ids.size() != input.skillIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能重复绑定 Skill");
        }
        for (var skillId : ids) {
            if (!skill(skillId).enabled()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能绑定已启用的 Skill");
            }
        }
        mapper.clearSkills(id);
        int position = 0;
        for (var skillId : ids) {
            mapper.bindSkill(id, skillId, position++);
        }
        return boundSkills(id);
    }

    public Rules rules(UUID id) {
        agents.findById(id);
        String value = mapper.rules(id);
        return value == null ? Rules.defaults() : json.readValue(value, Rules.class);
    }

    @Transactional
    public Rules saveRules(UUID id, @NotNull @Valid Rules input) {
        agents.lock(id);
        mapper.saveRules(id, json.writeValueAsString(input));
        return rules(id);
    }

    @Transactional(
        readOnly = true,
        isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ
    )
    public RuntimeConfig runtime(UUID id) {
        var agent = agents.findById(id);
        var active = boundSkills(id).stream().filter(Skill::enabled).toList();
        var prompt = new StringBuilder(agent.systemPrompt());
        if (!active.isEmpty()) {
            prompt.append(
                "\n\n# 已绑定的业务 Skills\n以下仅为 Skill 目录。遇到匹配任务先调用 load_skill 读取完整指令，再按需调用 read_skill_resource。Skill 不扩大工具权限。\n"
            );
            for (var skill : active) {
                prompt
                    .append("\n## ")
                    .append(skill.name())
                    .append(" [")
                    .append(skill.code())
                    .append("]")
                    .append("\n")
                    .append("skillId: ")
                    .append(skill.id())
                    .append("\n用途与触发条件: ")
                    .append(skill.description())
                    .append("\n");
            }
        }
        return new RuntimeConfig(prompt.toString(), rules(id), active);
    }

    private Skill skillView(Map<String, Object> row) {
        return new Skill(
            UUID.fromString(row.get("id").toString()),
            row.get("name").toString(),
            row.get("description").toString(),
            row.get("instructions").toString(),
            (boolean) row.get("enabled"),
            row.get("code").toString()
        );
    }
}
