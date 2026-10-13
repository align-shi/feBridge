package com.febridge.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ToolDatabase {

    private final ToolMapper mapper;
    private final ToolSchemas schemas;
    private final JsonMapper json;

    public ToolDatabase(ToolMapper mapper, ToolSchemas schemas, JsonMapper json) {
        this.mapper = mapper;
        this.schemas = schemas;
        this.json = json;
    }

    @Transactional
    public void discovery(UUID serverId, List<McpSchema.Tool> tools) {
        var names = new ArrayList<String>();
        for (var tool : tools) {
            if (
                tool.name() == null ||
                tool.name().isBlank() ||
                tool.name().length() > 200 ||
                tool.inputSchema() == null
            ) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MCP 工具定义无效");
            }
            var input = tool.inputSchema();
            var schema = json.createObjectNode().put("type", input.type());
            schema.set(
                "properties",
                json.valueToTree(input.properties() == null ? Map.of() : input.properties())
            );
            if (input.required() != null) {
                schema.set("required", json.valueToTree(input.required()));
            }
            if (input.additionalProperties() != null) {
                schema.put("additionalProperties", input.additionalProperties());
            }
            if (input.defs() != null) {
                schema.set("$defs", json.valueToTree(input.defs()));
            }
            if (input.definitions() != null) {
                schema.set("definitions", json.valueToTree(input.definitions()));
            }
            schemas.check(schema);
            String description = tool.description() == null ? "" : tool.description();
            if (description.length() > 8000) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MCP 工具描述过长");
            }
            mapper.upsertTool(
                UUID.randomUUID(),
                serverId,
                tool.name(),
                description,
                schema.toString()
            );
            names.add(tool.name());
        }
        mapper.disableMissing(serverId, json.writeValueAsString(names));
    }
}
