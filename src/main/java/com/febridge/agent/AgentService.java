package com.febridge.agent;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AgentService {

    private final AgentMapper mapper;
    private final JsonMapper json;
    private final com.febridge.model.ModelRegistry registry;

    public AgentService(
        AgentMapper mapper,
        JsonMapper json,
        com.febridge.model.ModelRegistry registry
    ) {
        this.mapper = mapper;
        this.json = json;
        this.registry = registry;
    }

    @Transactional
    public AgentDefinition create(CreateAgentRequest request) {
        if (request.modelId() != null) {
            registry.availableModel(request.modelId());
            registry.parameters(request.modelConfig());
        } else if (request.modelConfig() != null) {
            if (!request.modelConfig().isObject()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "模型参数必须是 JSON 对象"
                );
            }
            var validation = (tools.jackson.databind.node.ObjectNode) request
                .modelConfig()
                .deepCopy();
            validation.remove("model"); // Compatibility with pre-registry model names.
            registry.parameters(validation);
        }
        var agent = new AgentDefinition(
            UUID.randomUUID(),
            request.name().strip(),
            request.description() == null ? "" : request.description(),
            request.systemPrompt(),
            request.modelConfig() == null ? json.createObjectNode() : request.modelConfig(),
            OffsetDateTime.now(ZoneOffset.UTC),
            request.modelId()
        );
        mapper.insert(agent);
        return findById(agent.id());
    }

    public AgentDefinition findById(UUID id) {
        var agent = mapper.findById(id);
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found");
        }
        return agent;
    }

    public AgentDefinition lock(UUID id) {
        var agent = mapper.lock(id);
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found");
        }
        return agent;
    }

    @Transactional
    public AgentDefinition bind(UUID id, com.febridge.model.ModelDtos.Binding input) {
        lock(id);
        registry.availableModel(input.modelId());
        mapper.bind(id, input.modelId(), registry.parameters(input.parameters()));
        return findById(id);
    }

    public List<AgentDefinition> findRecent() {
        return mapper.findRecent();
    }
}
