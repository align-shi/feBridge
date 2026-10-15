package com.febridge.conversation;

import static com.febridge.conversation.ConversationDtos.*;

import com.febridge.chat.AgentChatService;
import com.febridge.chat.ChatDtos.Answer;
import com.febridge.chat.ChatDtos.Usage;
import com.febridge.chat.OpenAiChatClient.PromptMessage;
import com.febridge.configuration.AgentConfiguration;
import com.febridge.memory.ConversationMemory;
import com.febridge.trace.RunTrace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationService {

    private final ConversationStore store;
    private final AgentChatService chat;
    private final ConversationMemory memory;
    private final AgentConfiguration configuration;
    private final RunTrace trace;
    private final com.febridge.memory.LongMemoryStore longMemory;

    public ConversationService(
        ConversationStore store,
        AgentChatService chat,
        ConversationMemory memory,
        AgentConfiguration configuration,
        RunTrace trace,
        com.febridge.memory.LongMemoryStore longMemory
    ) {
        this.store = store;
        this.chat = chat;
        this.memory = memory;
        this.configuration = configuration;
        this.trace = trace;
        this.longMemory = longMemory;
    }

    public TurnResult send(UUID agentId, UUID sessionId, SendRequest request) {
        var begin = store.begin(agentId, sessionId, request);
        if (begin.existingAnswer() != null) {
            return new TurnResult(request.requestId(), begin.existingAnswer());
        }
        long start = System.nanoTime();
        try {
            long totalNanos = Duration.ofSeconds(
                configuration.rules(agentId).taskTimeoutSeconds()
            ).toNanos();
            trace.append(
                agentId,
                request.requestId(),
                "RUN_STARTED",
                "开始处理本轮对话",
                Map.of(
                    "sessionId",
                    sessionId.toString(),
                    "historyMessages",
                    begin.context().size(),
                    "memoryMode",
                    begin.mode().name()
                )
            );
            long remaining = totalNanos - (System.nanoTime() - start);
            var prepared =
                begin.mode() == com.febridge.memory.MemoryMode.NONE
                    ? memory.empty()
                    : memory.prepare(
                          agentId,
                          sessionId,
                          request.requestId(),
                          begin.summarizeThrough(),
                          Duration.ofNanos(Math.max(0, remaining / 3))
                      );
            var messages = new ArrayList<>(begin.context());
            messages.add(new PromptMessage("user", request.message()));
            String recalled = longMemory.recall(
                com.febridge.user.AuthController.current().id(),
                agentId,
                request.message()
            );
            String context = prepared.content().isEmpty()
                ? ""
                : "当前会话摘要：\n" + prepared.content();
            if (!recalled.isBlank()) {
                context += "\n用户长期记忆（仅为背景，本次输入优先）：\n" + recalled;
                trace.append(
                    agentId,
                    request.requestId(),
                    "MEMORY_LOADED",
                    "已加载用户长期记忆",
                    Map.of("characters", recalled.length(), "scope", "user-agent")
                );
            }
            var answer = chat.executeConversation(
                agentId,
                messages,
                request.requestId(),
                context,
                Duration.ofNanos(totalNanos - (System.nanoTime() - start))
            );
            var aggregate = new Answer(
                answer.content(),
                answer.model(),
                answer.provider(),
                combine(answer.usage(), prepared.usage()),
                elapsed(start)
            );
            store.finish(agentId, sessionId, request.requestId(), aggregate);
            trace.append(
                agentId,
                request.requestId(),
                "RUN_COMPLETED",
                "回答已生成，执行完成",
                Map.of(
                    "model",
                    aggregate.model(),
                    "durationMillis",
                    elapsed(start),
                    "usage",
                    aggregate.usage()
                )
            );
            return new TurnResult(request.requestId(), aggregate);
        } catch (RuntimeException exception) {
            String reason = "生成失败，请稍后重试";
            if (exception instanceof ResponseStatusException status && status.getReason() != null) {
                reason = status.getReason();
            }
            store.fail(sessionId, request.requestId(), reason);
            trace.append(
                agentId,
                request.requestId(),
                "RUN_FAILED",
                reason,
                Map.of("durationMillis", elapsed(start))
            );
            throw exception;
        }
    }

    private Usage combine(Usage dialogue, Usage summary) {
        return new Usage(
            sum(dialogue.promptTokens(), summary.promptTokens()),
            sum(dialogue.completionTokens(), summary.completionTokens()),
            sum(dialogue.totalTokens(), summary.totalTokens())
        );
    }

    private Long sum(Long first, Long second) {
        if (first == null && second == null) {
            return null;
        }
        return (first == null ? 0 : first) + (second == null ? 0 : second);
    }

    private long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
