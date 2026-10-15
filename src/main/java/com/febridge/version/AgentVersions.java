package com.febridge.version;

import static com.febridge.version.VersionDtos.*;

import com.febridge.agent.AgentService;
import com.febridge.configuration.AgentConfiguration;
import com.febridge.configuration.ConfigurationDtos.*;
import com.febridge.knowledge.KnowledgeStore;
import com.febridge.memory.LongMemoryStore;
import com.febridge.model.ModelDtos.Binding;
import com.febridge.model.ModelRegistry;
import com.febridge.tool.ToolDtos.ServerBinding;
import com.febridge.tool.ToolRegistry;
import com.febridge.user.AuthController;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AgentVersions {

    private final VersionMapper mapper;
    private final AgentService agents;
    private final AgentConfiguration configuration;
    private final ModelRegistry models;
    private final ToolRegistry tools;
    private final KnowledgeStore knowledge;
    private final LongMemoryStore memory;
    private final JsonMapper json;

    public AgentVersions(
        VersionMapper mapper,
        AgentService agents,
        AgentConfiguration configuration,
        ModelRegistry models,
        ToolRegistry tools,
        KnowledgeStore knowledge,
        LongMemoryStore memory,
        JsonMapper json
    ) {
        this.mapper = mapper;
        this.agents = agents;
        this.configuration = configuration;
        this.models = models;
        this.tools = tools;
        this.knowledge = knowledge;
        this.memory = memory;
        this.json = json;
    }

    private Map<String, Object> args(UUID agent) {
        return new HashMap<>(Map.of("agent", agent.toString()));
    }

    private Snapshot capture(UUID id) {
        var agent = agents.findById(id);
        var args = args(id);
        return new Snapshot(
            1,
            agent.name(),
            agent.description(),
            agent.systemPrompt(),
            agent.modelId(),
            agent.modelConfig(),
            configuration.rules(id),
            configuration.boundSkills(id).stream().map(Skill::id).toList(),
            mapper.mcpBindings(args).stream().map(UUID::fromString).toList(),
            mapper.knowledgeBindings(args).stream().map(UUID::fromString).toList(),
            memory.mode(AuthController.current().id(), id)
        );
    }

    @Transactional
    public State state(UUID id) {
        agents.lock(id);
        return stateLocked(id);
    }

    private State stateLocked(UUID id) {
        var draft = capture(id);
        String hash = fingerprint(json.valueToTree(draft));
        var args = args(id);
        var active = mapper.active(args);
        return new State(
            draft,
            hash,
            active == null ? null : UUID.fromString(active.get("id").toString()),
            active == null || !hash.equals(active.get("fingerprint")),
            mapper.versions(args).stream().map(this::view).toList()
        );
    }

    @Transactional
    public State publish(UUID id, Publish input) {
        agents.lock(id);
        var before = stateLocked(id);
        expect(before.fingerprint(), input.expectedFingerprint());
        if (before.draft().modelId() == null) {
            throw bad("发布前请绑定模型");
        }
        models.connection(before.draft().modelId());
        validateReferences(id, before.draft());
        if (!before.changed()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "草稿与当前发布版本一致，无需重复发布"
            );
        }
        var args = args(id);
        UUID version = UUID.randomUUID();
        int next = before.versions().isEmpty() ? 1 : before.versions().getFirst().number() + 1;
        args.put("version", version.toString());
        args.put("number", next);
        args.put("note", input.note().strip());
        args.put("snapshot", json.writeValueAsString(before.draft()));
        args.put("fingerprint", before.fingerprint());
        args.put("author", AuthController.current().id().toString());
        mapper.insert(args);
        mapper.activate(args);
        return stateLocked(id);
    }

    @Transactional
    public Map<String, Object> detail(UUID id, UUID version) {
        agents.findById(id);
        var row = require(id, version);
        return Map.of(
            "version",
            view(row),
            "snapshot",
            json.readTree(row.get("snapshot").toString())
        );
    }

    @Transactional
    public State activate(UUID id, UUID version) {
        agents.lock(id);
        require(id, version);
        var args = args(id);
        args.put("version", version.toString());
        mapper.activate(args);
        return stateLocked(id);
    }

    @Transactional
    public State restore(UUID id, UUID version, Expected input) {
        agents.lock(id);
        var before = stateLocked(id);
        expect(before.fingerprint(), input.expectedFingerprint());
        var row = require(id, version);
        var snapshot = json.readValue(row.get("snapshot").toString(), Snapshot.class);
        validateReferences(id, snapshot);
        memory.setMode(AuthController.current().id(), id, snapshot.memoryMode());
        configuration.profile(
            id,
            new Profile(snapshot.name(), snapshot.description(), snapshot.systemPrompt())
        );
        agents.bind(id, new Binding(snapshot.modelId(), snapshot.parameters()));
        configuration.saveRules(id, snapshot.rules());
        configuration.bind(id, new SkillBinding(snapshot.skillIds()));
        tools.bindServers(id, new ServerBinding(snapshot.mcpServerIds()));
        knowledge.bind(AuthController.current().id(), id, snapshot.knowledgeBaseIds());
        return stateLocked(id);
    }

    private void validateReferences(UUID id, Snapshot snapshot) {
        models.availableModel(snapshot.modelId());
        models.parameters(snapshot.parameters());
        for (UUID skill : snapshot.skillIds()) {
            if (!configuration.skill(skill).enabled()) {
                throw bad("版本引用的Skill已停用，请先处理后再操作");
            }
        }
        var available = tools
            .servers()
            .stream()
            .filter(server -> server.enabled())
            .map(server -> server.id())
            .collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(snapshot.mcpServerIds())) {
            throw bad("版本引用的MCP服务不存在或已停用");
        }
        for (UUID kb : snapshot.knowledgeBaseIds()) {
            knowledge.require(AuthController.current().id(), kb, false);
        }
    }

    private Map<String, Object> require(UUID id, UUID version) {
        var args = args(id);
        args.put("version", version.toString());
        var row = mapper.version(args);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        }
        return row;
    }

    private Version view(Map<String, Object> row) {
        Object time = row.get("created_at");
        String timestamp =
            time instanceof java.time.OffsetDateTime offset
                ? offset.toString()
                : ((Timestamp) time).toInstant().toString();
        return new Version(
            UUID.fromString(row.get("id").toString()),
            ((Number) row.get("number")).intValue(),
            row.get("note").toString(),
            row.get("fingerprint").toString(),
            UUID.fromString(row.get("published_by").toString()),
            timestamp
        );
    }

    private static void expect(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "草稿已被修改，请刷新核对后重试"
            );
        }
    }

    static String fingerprint(JsonNode node) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonical(node).getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final JsonMapper CANONICAL_JSON = JsonMapper.builder().build();

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, String>();
            for (var entry : node.properties()) {
                fields.put(entry.getKey(), canonical(entry.getValue()));
            }
            return fields
                .entrySet()
                .stream()
                .map(
                    entry ->
                        CANONICAL_JSON.writeValueAsString(entry.getKey()) + ":" + entry.getValue()
                )
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (node.isArray()) {
            var values = new ArrayList<String>();
            for (var value : node) {
                values.add(canonical(value));
            }
            return String.join(",", values).transform(value -> "[" + value + "]");
        }
        return node.toString();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
