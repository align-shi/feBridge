package com.febridge.tool;

import io.modelcontextprotocol.json.McpJsonDefaults;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ToolSchemas {

    private final JsonMapper json;

    public ToolSchemas(JsonMapper json) {
        this.json = json;
    }

    public void check(JsonNode schema) {
        if (
            schema == null ||
            !schema.isObject() ||
            !"object".equals(schema.path("type").asText()) ||
            schema.toString().length() > 32000
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "工具 Schema 必须为有限大小的 object 类型"
            );
        }
        checkReferences(schema, schema, 0);
    }

    private void checkReferences(JsonNode root, JsonNode node, int depth) {
        if (depth > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具 Schema 嵌套过深");
        }
        if (node.isObject()) {
            if (node.has("$id") || node.has("$dynamicRef")) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "暂不支持动态或外部 Schema 标识"
                );
            }
            if (node.has("$ref")) {
                String ref = node.get("$ref").asText();
                if (!ref.startsWith("#/") || root.at(ref.substring(1)).isMissingNode()) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "仅支持已存在的本地 Schema 引用"
                    );
                }
            }
            for (var property : node.properties()) {
                checkReferences(root, property.getValue(), depth + 1);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                checkReferences(root, node.get(index), depth + 1);
            }
        }
    }

    public Map<String, Object> arguments(JsonNode schema, String raw) {
        check(schema);
        if (raw == null || raw.length() > 32768) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具参数过大或为空");
        }
        JsonNode arguments;
        try {
            arguments = json.readTree(raw);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具参数不是合法 JSON");
        }
        if (arguments == null || !arguments.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具参数必须为 JSON 对象");
        }
        Map<String, Object> schemaMap = json.convertValue(schema, Map.class);
        Map<String, Object> value = json.convertValue(arguments, Map.class);
        if (!McpJsonDefaults.getSchemaValidator().validate(schemaMap, value).valid()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "工具参数不符合 Schema，请检查必填字段和类型"
            );
        }
        return value;
    }
}
