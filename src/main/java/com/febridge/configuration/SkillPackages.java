package com.febridge.configuration;

import com.febridge.configuration.ConfigurationDtos.Skill;
import com.febridge.configuration.ConfigurationDtos.SkillInput;
import com.febridge.tool.ToolDtos.ToolResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SkillPackages {

    public record Resource(
        @NotBlank @Size(max = 240) String path,
        @NotNull @Size(max = 1400000) String contentBase64,
        @NotBlank @Size(max = 100) String mediaType
    ) {}

    public record PackageInput(
        @NotNull @Valid SkillInput skill,
        @NotNull @Size(max = 32) List<@NotNull @Valid Resource> resources,
        @Size(max = 3) List<@NotNull @Pattern(regexp = "references|assets|scripts") String> folders
    ) {
        public PackageInput(SkillInput skill, List<Resource> resources) {
            this(skill, resources, null);
        }
    }

    public record FileView(String path, String mediaType, int sizeBytes, String contentBase64) {}

    public record Snapshot(Skill skill, List<FileView> resources) {}

    private final AgentConfiguration configuration;
    private final SkillResourceMapper mapper;
    private final JsonMapper json;

    public SkillPackages(
        AgentConfiguration configuration,
        SkillResourceMapper mapper,
        JsonMapper json
    ) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.json = json;
    }

    public List<FileView> resources(UUID id) {
        configuration.skill(id);
        return mapper
            .resources(id)
            .stream()
            .map(row ->
                new FileView(
                    row.get("path").toString(),
                    row.get("media_type").toString(),
                    ((Number) row.get("size_bytes")).intValue(),
                    row.get("content_base64").toString()
                )
            )
            .toList();
    }

    public List<String> folders(UUID id) {
        configuration.skill(id);
        return mapper.folders(id);
    }

    @Transactional
    public Skill save(UUID id, PackageInput input) {
        if (input.resources() == null || input.resources().size() > 32) {
            throw bad("每个 Skill 最多 32 个资源文件");
        }
        var folderNames = new LinkedHashSet<String>();
        if (input.folders() != null) {
            if (input.folders().size() > 3) {
                throw bad("最多创建 references、assets、scripts 三个根目录");
            }
            for (var folder : input.folders()) {
                if (
                    !List.of("references", "assets", "scripts").contains(folder) ||
                    !folderNames.add(folder)
                ) {
                    throw bad("文件夹名称不正确或重复");
                }
            }
        } else if (id != null) {
            folderNames.addAll(folders(id));
        }
        var paths = new HashSet<String>();
        int total = 0;
        for (var file : input.resources()) {
            validatePath(file.path());
            folderNames.add(file.path().split("/")[0]);
            if (!paths.add(file.path())) {
                throw bad("资源路径不能重复");
            }
            byte[] bytes = decode(file.contentBase64());
            if (bytes.length > 1048576) {
                throw bad("单个资源不能超过 1 MB");
            }
            total += bytes.length;
            if (total > 4194304) {
                throw bad("资源总大小不能超过 4 MB");
            }
            if (file.path().startsWith("references/") && text(bytes) == null) {
                throw bad("references 必须为 UTF-8 文本");
            }
        }
        var skill = configuration.saveSkill(id, input.skill());
        mapper.clear(skill.id());
        mapper.clearFolders(skill.id());
        for (var folder : folderNames) {
            mapper.saveFolder(skill.id(), folder);
        }
        for (var file : input.resources()) {
            mapper.save(
                skill.id(),
                file.path(),
                file.contentBase64(),
                file.mediaType(),
                decode(file.contentBase64()).length
            );
        }
        return skill;
    }

    public static void validatePath(String path) {
        if (
            path == null ||
            path.length() > 240 ||
            !(
                path.startsWith("references/") ||
                path.startsWith("assets/") ||
                path.startsWith("scripts/")
            ) ||
            path.contains("\\") ||
            path.contains(":") ||
            Arrays.stream(path.split("/", -1)).anyMatch(
                part -> part.isBlank() || part.equals(".") || part.equals("..")
            ) ||
            path.chars().anyMatch(Character::isISOControl)
        ) {
            throw bad("路径必须位于 references/、assets/ 或 scripts/，不能包含相对跳转");
        }
    }

    public record Prepared(ConfigurationDtos.RuntimeConfig runtime, List<Snapshot> snapshots) {}

    @Transactional(
        readOnly = true,
        isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ
    )
    public Prepared prepare(UUID agentId) {
        var runtime = configuration.runtime(agentId);
        var rules = runtime.rules();
        return new Prepared(
            runtime,
            rules.toolsEnabled() && rules.maxToolCalls() > 0
                ? snapshots(runtime.skills())
                : List.of()
        );
    }

    private List<Snapshot> snapshots(List<Skill> skills) {
        return skills
            .stream()
            .map(skill -> new Snapshot(skill, resources(skill.id())))
            .toList();
    }

    public List<JsonNode> definitions() {
        return List.of(
            definition(
                "load_skill",
                "读取已绑定 Skill 的完整指令及资源目录。先读取指令再遵循其步骤。",
                false
            ),
            definition(
                "read_skill_resource",
                "按路径读取已加载 Skill 的 references、assets 或 scripts。二进制仅返回文件信息，不执行代码。",
                true
            )
        );
    }

    private JsonNode definition(String name, String description, boolean resource) {
        var properties = json.createObjectNode();
        properties.set("skillId", json.createObjectNode().put("type", "string"));
        var required = json.createArrayNode().add("skillId");
        if (resource) {
            properties.set("path", json.createObjectNode().put("type", "string"));
            required.add("path");
        }
        var schema = json
            .createObjectNode()
            .put("type", "object")
            .put("additionalProperties", false);
        schema.set("properties", properties);
        schema.set("required", required);
        var function = json.createObjectNode().put("name", name).put("description", description);
        function.set("parameters", schema);
        return json.createObjectNode().put("type", "function").set("function", function);
    }

    public ToolResult execute(
        String name,
        String arguments,
        List<Snapshot> snapshots,
        Set<UUID> loaded
    ) {
        try {
            var args = json.readTree(arguments);
            int expected = name.equals("load_skill") ? 1 : 2;
            if (
                !args.isObject() ||
                args.size() != expected ||
                !args.path("skillId").isString() ||
                (!name.equals("load_skill") && !args.path("path").isString())
            ) {
                throw bad("Skill 工具参数不正确");
            }
            var id = UUID.fromString(args.path("skillId").asText());
            var snapshot = snapshots
                .stream()
                .filter(item -> item.skill().id().equals(id))
                .findFirst()
                .orElseThrow(() -> bad("Skill 未绑定或未启用"));
            if (name.equals("load_skill")) {
                loaded.add(id);
                return new ToolResult(
                    json.writeValueAsString(
                        Map.of(
                            "skillId",
                            id,
                            "instructions",
                            snapshot.skill().instructions(),
                            "resources",
                            snapshot
                                .resources()
                                .stream()
                                .map(file ->
                                    Map.of(
                                        "path",
                                        file.path(),
                                        "mediaType",
                                        file.mediaType(),
                                        "sizeBytes",
                                        file.sizeBytes()
                                    )
                                )
                                .toList()
                        )
                    ),
                    false
                );
            }
            if (!loaded.contains(id)) {
                throw bad("请先调用 load_skill");
            }
            String path = args.path("path").asText();
            validatePath(path);
            var file = snapshot
                .resources()
                .stream()
                .filter(item -> item.path().equals(path))
                .findFirst()
                .orElseThrow(() -> bad("资源不存在"));
            String text = text(decode(file.contentBase64()));
            var result = new LinkedHashMap<String, Object>();
            result.put("path", path);
            result.put("mediaType", file.mediaType());
            result.put("sizeBytes", file.sizeBytes());
            if (text != null) {
                result.put("content", text.substring(0, Math.min(text.length(), 12000)));
                result.put("truncated", text.length() > 12000);
            } else {
                result.put("binary", true);
                result.put(
                    "message",
                    "这是二进制附件，可在 Skills 管理下载；当前没有脚本执行或图像解析能力。"
                );
            }
            return new ToolResult(json.writeValueAsString(result), false);
        } catch (RuntimeException exception) {
            String message = "Skill 加载失败：参数、绑定权限或文件路径不正确";
            if (exception instanceof ResponseStatusException status && status.getReason() != null) {
                message = status.getReason();
            }
            return new ToolResult(json.writeValueAsString(Map.of("error", message)), true);
        }
    }

    private static byte[] decode(String content) {
        try {
            if (content == null || content.length() > 1400000) {
                throw bad("资源内容过大");
            }
            return Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException exception) {
            throw bad("文件内容不是合法 Base64");
        }
    }

    private static String text(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
            return value.indexOf(0) < 0 ? value : null;
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
