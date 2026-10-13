package com.febridge.conversation;

import static com.febridge.conversation.ConversationDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.febridge.agent.*;
import com.febridge.chat.ChatDtos.Usage;
import com.febridge.chat.OpenAiChatClient;
import com.febridge.chat.OpenAiChatClient.PromptMessage;
import com.febridge.model.ModelDtos.*;
import com.febridge.model.ModelRegistry;
import com.febridge.model.ProviderType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "platform.auth.bootstrap-enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DB_URL", matches = ".+")
class ConversationPersistenceTest {

    @Autowired
    ConversationStore store;

    @Autowired
    ConversationService service;

    @Autowired
    AgentService agents;

    @Autowired
    ModelRegistry models;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OpenAiChatClient client;

    private AgentDefinition agent;
    private final AtomicReference<List<PromptMessage>> lastMessages = new AtomicReference<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("PLATFORM_TEST_DB_URL"));
        properties.add("spring.datasource.username", () ->
            System.getenv("PLATFORM_TEST_DB_USERNAME")
        );
        properties.add("spring.datasource.password", () ->
            System.getenv("PLATFORM_TEST_DB_PASSWORD")
        );
    }

    @BeforeEach
    void setup() {
        var provider = models.createProvider(
            new ProviderInput(
                "会话测试",
                ProviderType.DEEPSEEK,
                "dummy-conversation-test-key",
                true
            )
        );
        var model = models.createModel(
            new ModelInput(
                provider.id(),
                "test-model",
                "测试模型",
                false,
                json.createObjectNode(),
                true
            )
        );
        agent = agents.create(
            new CreateAgentRequest(
                "会话助手",
                "",
                "你是测试助手",
                json.createObjectNode(),
                model.id()
            )
        );
        when(
            client.completeMessages(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyList(),
                any()
            )
        ).thenAnswer(invocation -> {
            List<PromptMessage> messages = invocation.getArgument(4);
            lastMessages.set(List.copyOf(messages));
            String content = messages.size() > 1 ? "你叫张三" : "这是一个独立会话";
            return new OpenAiChatClient.Result(content, new Usage(10L, 5L, 15L));
        });
    }

    @Test
    void savesDialogueReplaysRequestsAndSeparatesSessions() {
        var first = store.create(agent.id());
        UUID requestId = UUID.randomUUID();
        service.send(agent.id(), first.id(), new SendRequest(requestId, "我叫张三"));
        var history = store.history(agent.id(), first.id());
        assertEquals(2, history.messages().size());
        assertEquals("我叫张三", history.messages().getFirst().content());
        assertFalse(history.session().generating());

        service.send(agent.id(), first.id(), new SendRequest(requestId, "我叫张三"));
        verify(client, times(1)).completeMessages(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            any()
        );
        assertThrows(ResponseStatusException.class, () ->
            service.send(agent.id(), first.id(), new SendRequest(requestId, "不同的问题"))
        );

        var second = service.send(
            agent.id(),
            first.id(),
            new SendRequest(UUID.randomUUID(), "我叫什么")
        );
        assertEquals("你叫张三", second.answer().content());
        assertEquals(3, lastMessages.get().size());
        assertEquals("assistant", lastMessages.get().get(1).role());
        assertEquals(4, store.history(agent.id(), first.id()).messages().size());

        var fresh = store.create(agent.id());
        service.send(agent.id(), fresh.id(), new SendRequest(UUID.randomUUID(), "我叫什么"));
        assertEquals(1, lastMessages.get().size());
        assertEquals(2, store.list(agent.id()).size());

        var other = agents.create(
            new CreateAgentRequest("另一个助手", "", "测试", json.createObjectNode())
        );
        assertThrows(ResponseStatusException.class, () -> store.history(other.id(), first.id()));
    }

    @Test
    void rejectsConcurrentTurnsAndRecoversExpiredRequests() {
        var session = store.create(agent.id());
        UUID first = UUID.randomUUID();
        store.begin(agent.id(), session.id(), new SendRequest(first, "第一条问题"));
        var conflict = assertThrows(ResponseStatusException.class, () ->
            store.begin(agent.id(), session.id(), new SendRequest(UUID.randomUUID(), "并发问题"))
        );
        assertEquals(409, conflict.getStatusCode().value());

        jdbc.update(
            "UPDATE chat_session SET locked_until=CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id=?",
            session.id()
        );
        service.send(agent.id(), session.id(), new SendRequest(UUID.randomUUID(), "恢复后的问题"));
        var history = store.history(agent.id(), session.id());
        assertEquals("failed", history.messages().get(1).status());
        assertEquals("completed", history.messages().get(3).status());
        assertEquals(1, lastMessages.get().size());
        assertFalse(history.session().generating());
    }

    @Test
    void retainsFailuresWithoutFeedingThemToTheModel() {
        var session = store.create(agent.id());
        when(
            client.completeMessages(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyList(),
                any()
            )
        ).thenThrow(
            new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                "模拟厂商失败"
            )
        );
        assertThrows(ResponseStatusException.class, () ->
            service.send(agent.id(), session.id(), new SendRequest(UUID.randomUUID(), "失败的问题"))
        );
        var history = store.history(agent.id(), session.id());
        assertEquals("failed", history.messages().get(1).status());
        assertEquals("模拟厂商失败", history.messages().get(1).error());
        assertFalse(history.session().generating());
        assertTrue(
            store
                .begin(agent.id(), session.id(), new SendRequest(UUID.randomUUID(), "下一条"))
                .context()
                .isEmpty()
        );
    }
}
