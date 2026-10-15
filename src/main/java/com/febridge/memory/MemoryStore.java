package com.febridge.memory;

import static com.febridge.memory.MemoryDtos.*;

import com.febridge.chat.ChatDtos.Usage;
import com.febridge.user.AuthController;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MemoryStore {

    private final MemoryMapper mapper;
    private final JsonMapper json;

    public MemoryStore(MemoryMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public View view(UUID sessionId, long through) {
        var row = mapper.summary(sessionId);
        long covered = row == null ? 0 : number(row, "covered_sequence");
        long pending = mapper.pending(sessionId, covered, through);
        if (row == null) {
            return new View(
                json.createObjectNode(),
                "0",
                0,
                0,
                false,
                null,
                new Usage(null, null, null),
                null,
                null,
                pending
            );
        }
        return new View(
            json.readTree(row.get("content").toString()),
            Long.toString(covered),
            number(row, "covered_turns"),
            (int) number(row, "version"),
            Boolean.TRUE.equals(row.get("input_truncated")),
            (String) row.get("model_name"),
            new Usage(
                nullableNumber(row, "prompt_tokens"),
                nullableNumber(row, "completion_tokens"),
                nullableNumber(row, "total_tokens")
            ),
            number(row, "duration_millis"),
            time(row.get("updated_at")),
            pending
        );
    }

    public Plan plan(UUID sessionId, long through) {
        var previous = view(sessionId, through);
        return new Plan(
            previous,
            mapper.candidates(
                sessionId,
                Long.parseLong(previous.coveredThroughSequence()),
                through
            ),
            previous.pendingTurns()
        );
    }

    @Transactional
    public boolean save(
        UUID sessionId,
        UUID requestId,
        View previous,
        JsonNode content,
        long through,
        int turns,
        boolean truncated,
        String model,
        Usage usage,
        long duration
    ) {
        return (
            mapper.save(
                sessionId,
                requestId,
                AuthController.current().id(),
                previous.version(),
                content.toString(),
                through,
                previous.coveredTurns() + turns,
                previous.inputTruncated() || truncated,
                model,
                usage,
                duration
            ) == 1
        );
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private Long nullableNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    private String time(Object value) {
        if (value instanceof OffsetDateTime time) {
            return time.toString();
        }
        return ((Timestamp) value).toInstant().toString();
    }
}
