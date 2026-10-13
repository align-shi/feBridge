package com.febridge.trace;

import com.febridge.agent.AgentService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RunTrace {

    public record Event(
        String id,
        String kind,
        String summary,
        JsonNode details,
        String createdAt
    ) {}

    private final RunTraceMapper mapper;
    private final AgentService agents;
    private final JsonMapper json;

    public RunTrace(RunTraceMapper mapper, AgentService agents, JsonMapper json) {
        this.mapper = mapper;
        this.agents = agents;
        this.json = json;
    }

    public void append(UUID agentId, UUID requestId, String kind, String summary, Object details) {
        var value = redact(json.valueToTree(details), 0);
        String encoded = value.toString();
        if (encoded.length() > 12000) {
            value = json
                .createObjectNode()
                .put("preview", encoded.substring(0, 12000))
                .put("truncated", true);
        }
        mapper.append(
            agentId,
            requestId,
            kind,
            summary.length() > 300 ? summary.substring(0, 300) : summary,
            value.toString()
        );
    }

    public JsonNode snapshot(JsonNode value, int limit) {
        var safe = redact(value, 0);
        String encoded = safe.toString();
        if (encoded.length() <= limit) {
            return safe;
        }
        return json
            .createObjectNode()
            .put("preview", encoded.substring(0, limit))
            .put("truncated", true);
    }

    public JsonNode payload(String text) {
        try {
            return json.readTree(text);
        } catch (RuntimeException exception) {
            return json.createObjectNode().put("text", text);
        }
    }

    private JsonNode redact(JsonNode value, int depth) {
        if (value == null || depth > 16) {
            return json.createObjectNode().put("truncated", true);
        }
        if (value.isObject()) {
            var result = json.createObjectNode();
            for (var field : value.properties()) {
                String key = field.getKey().toLowerCase().replace("_", "").replace("-", "");
                if (key.equals("reasoningcontent") || key.equals("thinking")) {
                    continue;
                }
                if (
                    key.contains("password") ||
                    key.contains("secret") ||
                    key.contains("apikey") ||
                    key.contains("authorization") ||
                    (key.contains("token") && !key.endsWith("tokens"))
                ) {
                    result.put(field.getKey(), "[REDACTED]");
                } else {
                    result.set(field.getKey(), redact(field.getValue(), depth + 1));
                }
            }
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode();
            for (int i = 0; i < Math.min(value.size(), 100); i++) {
                result.add(redact(value.get(i), depth + 1));
            }
            if (value.size() > 100) {
                result.add("[TRUNCATED]");
            }
            return result;
        }
        if (value.isString()) {
            String text = value
                .asText()
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]");
            return json
                .getNodeFactory()
                .stringNode(
                    text.length() > 8000 ? text.substring(0, 8000) + "\n[TRUNCATED]" : text
                );
        }
        return value;
    }

    public List<Event> events(UUID agentId, UUID requestId) {
        agents.findById(agentId);
        return mapper
            .events(agentId, requestId)
            .stream()
            .map(row ->
                new Event(
                    row.get("id").toString(),
                    row.get("kind").toString(),
                    row.get("summary").toString(),
                    json.readTree(row.get("details").toString()),
                    row.get("created_at").toString()
                )
            )
            .toList();
    }
}
