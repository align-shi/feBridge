package com.febridge.conversation;

import static com.febridge.conversation.ConversationDtos.*;

import com.febridge.agent.AgentService;
import com.febridge.chat.ChatDtos.Answer;
import com.febridge.chat.ChatDtos.Usage;
import com.febridge.chat.OpenAiChatClient.PromptMessage;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationStore {

    private final ConversationMapper mapper;
    private final AgentService agents;

    public ConversationStore(ConversationMapper mapper, AgentService agents) {
        this.mapper = mapper;
        this.agents = agents;
    }

    public List<Session> list(UUID agentId) {
        agents.findById(agentId);
        return mapper.sessions(agentId).stream().map(this::sessionView).toList();
    }

    @Transactional
    public Session create(UUID agentId) {
        agents.findById(agentId);
        UUID id = UUID.randomUUID();
        mapper.createSession(id, agentId);
        return sessionView(requireSession(agentId, id, false));
    }

    @Transactional(
        readOnly = true,
        isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ
    )
    public History history(UUID agentId, UUID sessionId) {
        Map<String, Object> session = requireSession(agentId, sessionId, false);
        var messages = new ArrayList<Message>();
        for (Map<String, Object> row : mapper.turns(sessionId)) {
            UUID id = uuid(row, "id");
            String status = row.get("status").toString();
            String error = (String) row.get("error_message");
            if ("pending".equals(status) && !generating(session)) {
                status = "failed";
                error = "生成已中断，请重新发送";
            }
            messages.add(
                new Message(
                    id + ":user",
                    id,
                    "user",
                    row.get("user_content").toString(),
                    "completed",
                    null,
                    time(row, "created_at"),
                    null
                )
            );
            Answer answer = "completed".equals(status) ? answer(row) : null;
            OffsetDateTime createdAt =
                row.get("completed_at") == null
                    ? time(row, "created_at")
                    : time(row, "completed_at");
            messages.add(
                new Message(
                    id + ":assistant",
                    id,
                    "assistant",
                    answer == null ? "" : answer.content(),
                    status,
                    error,
                    createdAt,
                    answer
                )
            );
        }
        return new History(sessionView(session), messages);
    }

    // Lock only during short state transitions, never during a model HTTP call.
    @Transactional
    public Begin begin(UUID agentId, UUID sessionId, SendRequest request) {
        var session = requireSession(agentId, sessionId, true);
        var existing = mapper.turn(request.requestId());
        if (existing != null) {
            if (!sessionId.equals(uuid(existing, "session_id"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请求编号已被使用");
            }
            if (!request.message().equals(existing.get("user_content"))) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "同一请求编号不能用于不同消息"
                );
            }
            if ("completed".equals(existing.get("status"))) {
                return new Begin(answer(existing), List.of());
            }
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "该请求已受理，请刷新会话查看结果，不要重复发送"
            );
        }
        if (generating(session)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话正在生成回答");
        }
        mapper.interruptPending(sessionId);
        List<PromptMessage> context = buildContext(mapper.context(sessionId));
        mapper.insertTurn(request.requestId(), sessionId, request.message());
        String title = request.message().strip().replaceAll("\\s+", " ");
        if (title.length() > 40) {
            title = title.substring(0, 40);
        }
        mapper.start(sessionId, request.requestId(), title);
        return new Begin(null, context);
    }

    @Transactional
    public void finish(UUID agentId, UUID sessionId, UUID requestId, Answer answer) {
        var session = requireSession(agentId, sessionId, true);
        if (!requestId.equals(session.get("active_request"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请求已过期，请刷新会话");
        }
        mapper.finish(requestId, answer);
        mapper.release(sessionId, requestId);
    }

    @Transactional
    public void fail(UUID sessionId, UUID requestId, String error) {
        mapper.fail(requestId, error.length() > 300 ? error.substring(0, 300) : error);
        mapper.release(sessionId, requestId);
    }

    public record Begin(Answer existingAnswer, List<PromptMessage> context) {}

    static List<PromptMessage> buildContext(List<Map<String, Object>> rows) {
        int remaining = 24000;
        var selected = new ArrayList<Map<String, Object>>();
        for (int index = rows.size() - 1; index >= 0; index--) {
            var row = rows.get(index);
            int length =
                row.get("user_content").toString().length() +
                row.get("assistant_content").toString().length();
            if (length > remaining) {
                break;
            }
            remaining -= length;
            selected.addFirst(row);
        }
        var messages = new ArrayList<PromptMessage>();
        for (var row : selected) {
            messages.add(new PromptMessage("user", row.get("user_content").toString()));
            messages.add(new PromptMessage("assistant", row.get("assistant_content").toString()));
        }
        return messages;
    }

    private Map<String, Object> requireSession(UUID agentId, UUID id, boolean lock) {
        var row = lock ? mapper.lockSession(id, agentId) : mapper.session(id, agentId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return row;
    }

    private Session sessionView(Map<String, Object> row) {
        return new Session(
            uuid(row, "id"),
            uuid(row, "agent_id"),
            row.get("title").toString(),
            time(row, "created_at"),
            time(row, "updated_at"),
            generating(row)
        );
    }

    private boolean generating(Map<String, Object> row) {
        return (
            row.get("active_request") != null &&
            row.get("locked_until") != null &&
            time(row, "locked_until").isAfter(OffsetDateTime.now(ZoneOffset.UTC))
        );
    }

    private Answer answer(Map<String, Object> row) {
        return new Answer(
            row.get("assistant_content").toString(),
            (String) row.get("model_name"),
            (String) row.get("provider_name"),
            new Usage(
                number(row, "prompt_tokens"),
                number(row, "completion_tokens"),
                number(row, "total_tokens")
            ),
            number(row, "duration_millis")
        );
    }

    private Long number(Map<String, Object> row, String name) {
        Object value = row.get(name);
        return value == null ? null : ((Number) value).longValue();
    }

    private UUID uuid(Map<String, Object> row, String name) {
        return UUID.fromString(row.get(name).toString());
    }

    private OffsetDateTime time(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (value instanceof OffsetDateTime time) {
            return time;
        }
        return ((Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
    }
}
