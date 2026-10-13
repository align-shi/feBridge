package com.febridge.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.febridge.chat.ChatDtos.Usage;
import com.febridge.chat.OpenAiChatClient;
import com.febridge.tool.ToolSchemas;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ModelToolProbeTest {

    final JsonMapper json = JsonMapper.builder().build();
    final ModelRegistry registry = mock(ModelRegistry.class);
    final OpenAiChatClient client = mock(OpenAiChatClient.class);
    final UUID id = UUID.randomUUID();
    ModelToolProbe probe;

    @BeforeEach
    void setup() {
        var model = new ModelDtos.ModelView(
            id,
            UUID.randomUUID(),
            "test",
            "测试模型",
            false,
            json.createObjectNode(),
            true,
            null
        );
        when(registry.connection(id)).thenReturn(
            new ModelRegistry.Connection(model, "http://fixture", "dummy", "测试")
        );
        probe = new ModelToolProbe(registry, client, new ToolSchemas(json), json);
    }

    @Test
    void verifiesToolCallSchemaAndResultRoundTripEvenWhenFlagIsFalse() {
        when(
            client.completeTurn(
                anyString(),
                anyString(),
                anyString(),
                anyList(),
                any(),
                anyList(),
                any()
            )
        ).thenAnswer(invocation -> {
            List<JsonNode> messages = invocation.getArgument(3);
            if (messages.getLast().path("role").asText().equals("tool")) {
                String nonce = json
                    .readTree(messages.getLast().path("content").asText())
                    .path("value")
                    .asText();
                assertEquals("probe1", messages.getLast().path("tool_call_id").asText());
                return new OpenAiChatClient.Result(nonce, new Usage(1L, 1L, 2L));
            }
            List<JsonNode> tools = invocation.getArgument(5);
            String nonce = tools
                .getFirst()
                .path("function")
                .path("parameters")
                .path("properties")
                .path("value")
                .path("enum")
                .path(0)
                .asText();
            var assistant = json.createObjectNode().put("role", "assistant");
            return new OpenAiChatClient.Result(
                "",
                new Usage(1L, 1L, 2L),
                assistant,
                List.of(
                    new OpenAiChatClient.ToolCall(
                        "probe1",
                        "febridge_probe_echo",
                        json.createObjectNode().put("value", nonce).toString()
                    )
                )
            );
        });
        assertTrue(probe.test(id).passed());
        verify(client, times(2)).completeTurn(
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            any(),
            anyList(),
            any()
        );
        verify(registry, never()).updateModel(any(), any());
    }

    @Test
    void plainTextDoesNotProveToolCapability() {
        when(
            client.completeTurn(
                anyString(),
                anyString(),
                anyString(),
                anyList(),
                any(),
                anyList(),
                any()
            )
        ).thenReturn(new OpenAiChatClient.Result("我支持工具调用", new Usage(1L, 1L, 2L)));
        assertFalse(probe.test(id).passed());
        verify(client, times(1)).completeTurn(
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            any(),
            anyList(),
            any()
        );
    }

    @Test
    void rejectsInvalidParametersBeforeSimulatingTheTool() {
        when(
            client.completeTurn(
                anyString(),
                anyString(),
                anyString(),
                anyList(),
                any(),
                anyList(),
                any()
            )
        ).thenReturn(
            new OpenAiChatClient.Result(
                "",
                new Usage(1L, 1L, 2L),
                json.createObjectNode(),
                List.of(new OpenAiChatClient.ToolCall("probe1", "febridge_probe_echo", "{}"))
            )
        );
        assertFalse(probe.test(id).passed());
        verify(client, times(1)).completeTurn(
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            any(),
            anyList(),
            any()
        );
    }
}
