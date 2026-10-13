package com.febridge.chat;

import static com.febridge.chat.ChatDtos.*;

import com.febridge.agent.AgentService;
import com.febridge.configuration.AgentConfiguration;
import com.febridge.configuration.ConfigurationDtos.FailurePolicy;
import com.febridge.model.ModelRegistry;
import com.febridge.tool.ToolExecutor;
import com.febridge.trace.RunTrace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AgentChatService {

    private final AgentService agents;
    private final ModelRegistry registry;
    private final OpenAiChatClient client;
    private final ToolExecutor executor;
    private final JsonMapper json;
    private final AgentConfiguration configuration;
    private final RunTrace trace;

    public AgentChatService(
        AgentService agents,
        ModelRegistry registry,
        OpenAiChatClient client,
        ToolExecutor executor,
        JsonMapper json,
        AgentConfiguration configuration,
        RunTrace trace
    ) {
        this.agents = agents;
        this.registry = registry;
        this.client = client;
        this.executor = executor;
        this.json = json;
        this.configuration = configuration;
        this.trace = trace;
    }

    public Answer chat(UUID agentId, Request request) {
        return chatMessages(
            agentId,
            List.of(new OpenAiChatClient.PromptMessage("user", request.message()))
        );
    }

    public Answer chatMessages(UUID agentId, List<OpenAiChatClient.PromptMessage> messages) {
        return chatMessages(agentId, messages, UUID.randomUUID());
    }

    // Network calls deliberately run outside database transactions.
    public Answer chatMessages(
        UUID agentId,
        List<OpenAiChatClient.PromptMessage> history,
        UUID requestId
    ) {
        agents.findById(agentId);
        long runStart = System.nanoTime();
        trace.append(
            agentId,
            requestId,
            "RUN_STARTED",
            "开始处理本轮对话",
            java.util.Map.of("historyMessages", history.size())
        );
        try {
            var answer = execute(agentId, history, requestId);
            trace.append(
                agentId,
                requestId,
                "RUN_COMPLETED",
                "回答已生成，执行完成",
                java.util.Map.of(
                    "model",
                    answer.model(),
                    "durationMillis",
                    elapsed(runStart),
                    "usage",
                    answer.usage()
                )
            );
            return answer;
        } catch (RuntimeException exception) {
            String reason = "执行失败";
            if (exception instanceof ResponseStatusException status && status.getReason() != null) {
                reason = status.getReason();
            }
            trace.append(
                agentId,
                requestId,
                "RUN_FAILED",
                reason,
                java.util.Map.of("durationMillis", elapsed(runStart))
            );
            throw exception;
        }
    }

    private Answer execute(
        UUID agentId,
        List<OpenAiChatClient.PromptMessage> history,
        UUID requestId
    ) {
        long start = System.nanoTime();
        var agent = agents.findById(agentId);
        if (agent.modelId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先为智能体绑定模型");
        }
        var connection = registry.connection(agent.modelId());
        var parameters = json.createObjectNode();
        var defaults = connection.model().defaultParameters();
        for (var entry : defaults.properties()) {
            parameters.set(entry.getKey(), entry.getValue());
        }
        if (agent.modelConfig() != null) {
            for (var entry : agent.modelConfig().properties()) {
                if (!"model".equals(entry.getKey())) {
                    parameters.set(entry.getKey(), entry.getValue());
                }
            }
        }
        registry.parameters(parameters);
        var runtime = configuration.runtime(agentId);
        var rules = runtime.rules();
        var available =
            rules.toolsEnabled() && rules.maxToolCalls() > 0
                ? executor.available(agentId)
                : java.util.List.<com.febridge.tool.ToolDtos.ToolView>of();
        trace.append(
            agentId,
            requestId,
            "CONTEXT_PREPARED",
            "已组装提示词、Skills 和可用工具",
            java.util.Map.of(
                "durationMillis",
                elapsed(start),
                "model",
                connection.model().modelCode(),
                "promptCharacters",
                runtime.systemPrompt().length(),
                "skills",
                runtime
                    .skills()
                    .stream()
                    .map(skill -> skill.name())
                    .toList(),
                "tools",
                available
                    .stream()
                    .map(tool ->
                        java.util.Map.of(
                            "name",
                            tool.name(),
                            "alias",
                            tool.alias(),
                            "server",
                            tool.serverName()
                        )
                    )
                    .toList(),
                "rules",
                rules
            )
        );
        if (available.isEmpty()) {
            var promptMessages = new ArrayList<JsonNode>();
            promptMessages.add(
                json.createObjectNode().put("role", "system").put("content", runtime.systemPrompt())
            );
            for (var message : history) {
                promptMessages.add(
                    json
                        .createObjectNode()
                        .put("role", message.role())
                        .put("content", message.content())
                );
            }
            modelRequest(
                agentId,
                requestId,
                1,
                connection.model().modelCode(),
                connection.providerName(),
                promptMessages,
                parameters,
                0
            );
            long modelStart = System.nanoTime();
            OpenAiChatClient.Result result;
            if (rules.taskTimeoutSeconds() >= 120) {
                result = client.completeMessages(
                    connection.baseUrl(),
                    connection.apiKey(),
                    connection.model().modelCode(),
                    runtime.systemPrompt(),
                    history,
                    parameters
                );
            } else {
                result = client.completeMessages(
                    connection.baseUrl(),
                    connection.apiKey(),
                    connection.model().modelCode(),
                    runtime.systemPrompt(),
                    history,
                    parameters,
                    Duration.ofSeconds(rules.taskTimeoutSeconds())
                );
            }
            modelResponse(agentId, requestId, 1, result, elapsed(modelStart));
            if (!result.calls().isEmpty()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "模型请求工具，但智能体未配置可用工具"
                );
            }
            return new Answer(
                result.content(),
                connection.model().modelCode(),
                connection.providerName(),
                result.usage(),
                elapsed(start)
            );
        }
        if (!connection.model().supportsTools()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "绑定模型尚未标记支持工具调用"
            );
        }
        var definitions = new ArrayList<JsonNode>();
        for (var tool : available) {
            var function = json
                .createObjectNode()
                .put("name", tool.alias())
                .put(
                    "description",
                    tool.name() + "（" + tool.serverName() + "）\n" + tool.description()
                );
            function.set("parameters", tool.inputSchema());
            var definition = json.createObjectNode().put("type", "function");
            definition.set("function", function);
            definitions.add(definition);
        }
        var messages = new ArrayList<JsonNode>();
        messages.add(
            json.createObjectNode().put("role", "system").put("content", runtime.systemPrompt())
        );
        for (var message : history) {
            messages.add(
                json
                    .createObjectNode()
                    .put("role", message.role())
                    .put("content", message.content())
            );
        }
        long deadline = start + Duration.ofSeconds(rules.taskTimeoutSeconds()).toNanos();
        var seen = new HashSet<String>();
        int calls = 0;
        var usage = new UsageCounter();
        for (int step = 0; step < rules.maxModelCalls(); step++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "智能体任务超过总时间限制"
                );
            }
            modelRequest(
                agentId,
                requestId,
                step + 1,
                connection.model().modelCode(),
                connection.providerName(),
                messages,
                parameters,
                definitions.size()
            );
            long modelStart = System.nanoTime();
            var result = client.completeTurn(
                connection.baseUrl(),
                connection.apiKey(),
                connection.model().modelCode(),
                messages,
                parameters,
                definitions,
                Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(120).toNanos()))
            );
            modelResponse(agentId, requestId, step + 1, result, elapsed(modelStart));
            usage.add(result.usage());
            if (result.calls().isEmpty()) {
                return new Answer(
                    result.content(),
                    connection.model().modelCode(),
                    connection.providerName(),
                    usage.value(),
                    elapsed(start)
                );
            }
            if (
                step == rules.maxModelCalls() - 1 ||
                calls + result.calls().size() > rules.maxToolCalls()
            ) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "智能体已达到执行步骤或工具次数上限"
                );
            }
            messages.add(result.assistantMessage());
            for (var call : result.calls()) {
                if (!seen.add(call.id())) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "模型返回重复的工具调用编号"
                    );
                }
                long seconds = (deadline - System.nanoTime()) / 1_000_000_000;
                if (seconds < 1) {
                    throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "智能体任务已超时"
                    );
                }
                String toolName = available
                    .stream()
                    .filter(tool -> tool.alias().equals(call.name()))
                    .map(tool -> tool.serverName() + " / " + tool.name())
                    .findFirst()
                    .orElse(call.name());
                trace.append(
                    agentId,
                    requestId,
                    "TOOL_REQUEST",
                    "调用工具：" + toolName,
                    java.util.Map.of(
                        "callId",
                        call.id(),
                        "step",
                        step + 1,
                        "tool",
                        toolName,
                        "arguments",
                        trace.payload(call.arguments())
                    )
                );
                long toolStart = System.nanoTime();
                var output = executor.execute(
                    agentId,
                    requestId,
                    call.id(),
                    call.name(),
                    call.arguments(),
                    (int) Math.min(seconds, rules.toolTimeoutSeconds())
                );
                trace.append(
                    agentId,
                    requestId,
                    "TOOL_RESULT",
                    toolName + (output.error() ? " 执行失败" : " 执行完成"),
                    java.util.Map.of(
                        "callId",
                        call.id(),
                        "error",
                        output.error(),
                        "durationMillis",
                        elapsed(toolStart),
                        "result",
                        trace.payload(output.content())
                    )
                );
                if (output.error() && rules.failurePolicy() == FailurePolicy.STOP) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "工具执行失败，已按执行规则终止任务"
                    );
                }
                messages.add(
                    json
                        .createObjectNode()
                        .put("role", "tool")
                        .put("tool_call_id", call.id())
                        .put("content", output.content())
                );
                calls++;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "智能体执行结束但未生成回答");
    }

    private void modelRequest(
        UUID agentId,
        UUID requestId,
        int step,
        String model,
        String provider,
        List<JsonNode> messages,
        JsonNode parameters,
        int toolCount
    ) {
        // Bound input independently so metrics and correlation fields survive truncation.
        var snapshot = json.createArrayNode();
        int perMessage = Math.min(2000, 6000 / Math.max(1, messages.size()));
        boolean truncated = false;
        for (var message : messages) {
            var safe = trace.snapshot(message, perMessage);
            snapshot.add(safe);
            truncated |= safe.path("truncated").asBoolean();
        }
        trace.append(
            agentId,
            requestId,
            "MODEL_REQUEST",
            "第 " + step + " 次请求模型",
            java.util.Map.of(
                "step",
                step,
                "model",
                model,
                "provider",
                provider,
                "messageCount",
                messages.size(),
                "toolCount",
                toolCount,
                "parameters",
                trace.snapshot(parameters, 1000),
                "messages",
                snapshot,
                "inputTruncated",
                truncated
            )
        );
    }

    private void modelResponse(
        UUID agentId,
        UUID requestId,
        int step,
        OpenAiChatClient.Result result,
        long duration
    ) {
        trace.append(
            agentId,
            requestId,
            "MODEL_RESPONSE",
            "第 " +
                step +
                " 次模型响应：" +
                (result.calls().isEmpty()
                    ? "返回回答"
                    : "请求 " + result.calls().size() + " 个工具"),
            java.util.Map.of(
                "step",
                step,
                "durationMillis",
                duration,
                "usage",
                result.usage(),
                "content",
                result.content(),
                "calls",
                result
                    .calls()
                    .stream()
                    .map(call -> java.util.Map.of("id", call.id(), "name", call.name()))
                    .toList()
            )
        );
    }

    private long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static class UsageCounter {

        private Long prompt;
        private Long completion;
        private Long total;

        void add(Usage usage) {
            prompt = sum(prompt, usage.promptTokens());
            completion = sum(completion, usage.completionTokens());
            total = sum(total, usage.totalTokens());
        }

        private Long sum(Long left, Long right) {
            if (right == null) {
                return left;
            }
            return (left == null ? 0 : left) + right;
        }

        Usage value() {
            return new Usage(prompt, completion, total);
        }
    }
}
