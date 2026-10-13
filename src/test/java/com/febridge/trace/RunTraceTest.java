package com.febridge.trace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.febridge.agent.AgentService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class RunTraceTest {

    @Test
    void sanitizesBeforeTruncatingInputSnapshots() {
        var json = JsonMapper.builder().build();
        var trace = new RunTrace(mock(RunTraceMapper.class), mock(AgentService.class), json);
        var input = json
            .createObjectNode()
            .put("api_key", "private-key")
            .put("reasoning_content", "internal-reasoning")
            .put("content", "x".repeat(10000));
        String value = trace.snapshot(input, 500).toString();
        assertFalse(value.contains("private-key"));
        assertFalse(value.contains("internal-reasoning"));
        assertTrue(value.contains("truncated"));
        assertTrue(value.length() < 1000);
    }

    @Test
    void redactsCredentialsAndBoundsLargeDetails() {
        var mapper = mock(RunTraceMapper.class);
        var json = JsonMapper.builder().build();
        var trace = new RunTrace(mapper, mock(AgentService.class), json);
        UUID agent = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        trace.append(
            agent,
            request,
            "TOOL_RESULT",
            "结果",
            Map.of(
                "api_key",
                "must-not-leak",
                "nested",
                Map.of("Authorization", "must-not-leak"),
                "text",
                "Bearer must-not-leak",
                "data",
                "x".repeat(20000),
                "usage",
                Map.of("totalTokens", 12)
            )
        );
        var captured = ArgumentCaptor.forClass(String.class);
        verify(mapper).append(
            eq(agent),
            eq(request),
            eq("TOOL_RESULT"),
            eq("结果"),
            captured.capture()
        );
        assertFalse(captured.getValue().contains("must-not-leak"));
        assertTrue(captured.getValue().length() < 13000);
        assertTrue(captured.getValue().contains("totalTokens"));
    }
}
