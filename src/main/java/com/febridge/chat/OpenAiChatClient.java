package com.febridge.chat;

import static com.febridge.chat.ChatDtos.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OpenAiChatClient {

    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public OpenAiChatClient(JsonMapper json) {
        this.json = json;
    }

    public Result complete(
        String baseUrl,
        String key,
        String model,
        String system,
        String message,
        JsonNode parameters
    ) {
        return completeMessages(
            baseUrl,
            key,
            model,
            system,
            List.of(new PromptMessage("user", message)),
            parameters
        );
    }

    public record PromptMessage(String role, String content) {}

    public record ToolCall(String id, String name, String arguments) {}

    public record Result(
        String content,
        Usage usage,
        JsonNode assistantMessage,
        List<ToolCall> calls
    ) {
        public Result(String content, Usage usage) {
            this(content, usage, null, List.of());
        }
    }

    public Result completeMessages(
        String baseUrl,
        String key,
        String model,
        String system,
        List<PromptMessage> history,
        JsonNode parameters
    ) {
        return completeMessages(
            baseUrl,
            key,
            model,
            system,
            history,
            parameters,
            Duration.ofSeconds(120)
        );
    }

    public Result completeMessages(
        String baseUrl,
        String key,
        String model,
        String system,
        List<PromptMessage> history,
        JsonNode parameters,
        Duration timeout
    ) {
        var messages = new ArrayList<JsonNode>();
        messages.add(json.createObjectNode().put("role", "system").put("content", system));
        for (var message : history) {
            messages.add(
                json
                    .createObjectNode()
                    .put("role", message.role())
                    .put("content", message.content())
            );
        }
        return completeTurn(baseUrl, key, model, messages, parameters, List.of(), timeout);
    }

    public Result completeTurn(
        String baseUrl,
        String key,
        String model,
        List<JsonNode> messages,
        JsonNode parameters,
        List<JsonNode> tools,
        Duration timeout
    ) {
        var body = json.createObjectNode();
        if (parameters != null) {
            parameters.properties().forEach(entry -> body.set(entry.getKey(), entry.getValue()));
        }
        body.put("model", model).put("stream", false);
        body.set("messages", json.valueToTree(messages));
        if (!tools.isEmpty()) {
            body.set("tools", json.valueToTree(tools));
            body.put("tool_choice", "auto");
        }
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                fail(HttpStatus.BAD_GATEWAY, "模型厂商鉴权失败，请检查 API Key 和模型权限");
            }
            if (status == 429) {
                fail(HttpStatus.TOO_MANY_REQUESTS, "模型厂商限流或额度不足，请稍后重试并检查余额");
            }
            if (status < 200 || status >= 300) {
                fail(
                    HttpStatus.BAD_GATEWAY,
                    "模型厂商返回错误（HTTP " + status + "），请检查模型标识与参数"
                );
            }
            JsonNode result;
            try {
                result = json.readTree(response.body());
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型响应格式无效");
            }
            if (result == null) {
                fail(HttpStatus.BAD_GATEWAY, "模型响应为空");
            }
            var message = result.path("choices").path(0).path("message");
            var calls = new ArrayList<ToolCall>();
            var ids = new HashSet<String>();
            var rawCalls = message.path("tool_calls");
            if (!rawCalls.isMissingNode() && !rawCalls.isNull()) {
                if (!rawCalls.isArray() || rawCalls.size() > 12) {
                    fail(HttpStatus.BAD_GATEWAY, "模型工具调用格式无效或数量过多");
                }
                for (int index = 0; index < rawCalls.size(); index++) {
                    var call = rawCalls.get(index);
                    String id = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String arguments = call.path("function").path("arguments").asText();
                    if (
                        !"function".equals(call.path("type").asText()) ||
                        id.isBlank() ||
                        id.length() > 100 ||
                        !ids.add(id) ||
                        name.isBlank() ||
                        name.length() > 64 ||
                        arguments.length() > 32768
                    ) {
                        fail(HttpStatus.BAD_GATEWAY, "模型工具调用字段无效");
                    }
                    calls.add(new ToolCall(id, name, arguments));
                }
            }
            String text = message.path("content").isString() ? message.get("content").asText() : "";
            if (calls.isEmpty() && text.isBlank()) {
                fail(HttpStatus.BAD_GATEWAY, "模型未返回文本回答");
            }
            var assistant = json.createObjectNode().put("role", "assistant");
            if (message.has("content")) {
                assistant.set("content", message.get("content"));
            }
            if (message.has("reasoning_content")) {
                assistant.set("reasoning_content", message.get("reasoning_content"));
            }
            if (!calls.isEmpty()) {
                assistant.set("tool_calls", rawCalls);
            }
            var usage = result.path("usage");
            return new Result(
                text,
                new Usage(
                    number(usage, "prompt_tokens"),
                    number(usage, "completion_tokens"),
                    number(usage, "total_tokens")
                ),
                assistant,
                List.copyOf(calls)
            );
        } catch (HttpTimeoutException exception) {
            throw new ResponseStatusException(
                HttpStatus.GATEWAY_TIMEOUT,
                "模型调用超时，请稍后重试"
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型调用被中断");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接模型服务");
        }
    }

    private static Long number(JsonNode node, String name) {
        return node.path(name).isIntegralNumber() ? node.get(name).asLong() : null;
    }

    private static void fail(HttpStatus status, String message) {
        throw new ResponseStatusException(status, message);
    }
}
