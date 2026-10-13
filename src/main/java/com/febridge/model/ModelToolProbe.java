package com.febridge.model;

import com.febridge.chat.OpenAiChatClient;
import com.febridge.tool.ToolSchemas;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ModelToolProbe {

    public record Outcome(boolean passed, String message, long durationMillis) {}

    private final ModelRegistry registry;
    private final OpenAiChatClient client;
    private final ToolSchemas schemas;
    private final JsonMapper json;

    public ModelToolProbe(
        ModelRegistry registry,
        OpenAiChatClient client,
        ToolSchemas schemas,
        JsonMapper json
    ) {
        this.registry = registry;
        this.client = client;
        this.schemas = schemas;
        this.json = json;
    }

    public Outcome test(UUID modelId) {
        var connection = registry.connection(modelId);
        long start = System.nanoTime();
        String nonce = UUID.randomUUID().toString();
        var schema = json
            .createObjectNode()
            .put("type", "object")
            .put("additionalProperties", false);
        schema.set(
            "properties",
            json
                .createObjectNode()
                .set(
                    "value",
                    json
                        .createObjectNode()
                        .put("type", "string")
                        .set("enum", json.createArrayNode().add(nonce))
                )
        );
        schema.set("required", json.createArrayNode().add("value"));
        var function = json
            .createObjectNode()
            .put("name", "febridge_probe_echo")
            .put("description", "无副作用的测试工具，返回输入值。");
        function.set("parameters", schema);
        var definition = json.createObjectNode().put("type", "function").set("function", function);
        var messages = new ArrayList<JsonNode>();
        messages.add(
            json
                .createObjectNode()
                .put("role", "system")
                .put(
                    "content",
                    "这是工具调用能力测试。必须先调用提供的测试工具；收到工具结果后，仅输出其中的 value，不再调用工具。"
                )
        );
        messages.add(
            json
                .createObjectNode()
                .put("role", "user")
                .put("content", "请调用 febridge_probe_echo，value 为 " + nonce + "。")
        );
        var first = client.completeTurn(
            connection.baseUrl(),
            connection.apiKey(),
            connection.model().modelCode(),
            messages,
            connection.model().defaultParameters(),
            List.of(definition),
            Duration.ofSeconds(30)
        );
        if (first.calls().size() != 1) {
            return outcome(false, "本次未返回单个工具调用，尚不能确认支持。", start);
        }
        var call = first.calls().getFirst();
        if (!"febridge_probe_echo".equals(call.name())) {
            return outcome(false, "返回了非预期工具名，测试未通过。", start);
        }
        try {
            schemas.arguments(schema, call.arguments());
        } catch (RuntimeException exception) {
            return outcome(false, "工具参数不符合测试 Schema，测试未通过。", start);
        }
        messages.add(first.assistantMessage());
        messages.add(
            json
                .createObjectNode()
                .put("role", "tool")
                .put("tool_call_id", call.id())
                .put("content", json.createObjectNode().put("value", nonce).toString())
        );
        var second = client.completeTurn(
            connection.baseUrl(),
            connection.apiKey(),
            connection.model().modelCode(),
            messages,
            connection.model().defaultParameters(),
            List.of(definition),
            Duration.ofSeconds(30)
        );
        boolean passed = second.calls().isEmpty() && second.content().contains(nonce);
        return outcome(
            passed,
            passed
                ? "已验证工具指令、参数 Schema 和工具结果回传。"
                : "工具指令已返回，但结果回传后的回答未通过验证。",
            start
        );
    }

    private Outcome outcome(boolean passed, String message, long start) {
        return new Outcome(passed, message, (System.nanoTime() - start) / 1_000_000);
    }
}
