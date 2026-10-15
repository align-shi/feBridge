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

@SpringBootTest(
    properties = { "platform.auth.bootstrap-enabled=false", "platform.memory.worker-enabled=false" }
)
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

    @Autowired
    com.febridge.user.UserService users;

    @Autowired
    com.febridge.trace.RunTrace trace;

    @Autowired
    com.febridge.memory.MemoryStore memoryStore;

    private final AtomicReference<String> lastSystem = new AtomicReference<>();
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
        var user = users.create(
            new com.febridge.user.UserDtos.CreateUser(
                "member_" + UUID.randomUUID().toString().substring(0, 20),
                "会话测试用户",
                "Test-password-12345",
                com.febridge.user.UserDtos.Role.USER
            )
        );
        authenticate(user.id());
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
        longMemory.policy(com.febridge.user.AuthController.current().id(), agent.id(), true);
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
            lastSystem.set(invocation.getArgument(3));
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

    @Test
    void persistsIncrementalSummaryAndFeedsItToTheNextModelCall() {
        var session = store.create(agent.id());
        seed(session.id(), 11);
        var summaryInputs = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        when(
            client.completeMessages(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyList(),
                any(),
                any(java.time.Duration.class)
            )
        ).thenAnswer(invocation -> {
            List<PromptMessage> prompts = invocation.getArgument(4);
            summaryInputs.add(json.readTree(prompts.getFirst().content()));
            return new OpenAiChatClient.Result(
                "{\"goal\":\"了解用户信息\",\"confirmedFacts\":[\"用户叫张三\"]," +
                    "\"pendingQuestions\":[],\"references\":[]}",
                new Usage(20L, 5L, 25L)
            );
        });
        UUID firstRequest = UUID.randomUUID();
        var answer = service.send(
            agent.id(),
            session.id(),
            new SendRequest(firstRequest, "我叫什么")
        );
        assertEquals(40L, answer.answer().usage().totalTokens());
        var first = store.history(agent.id(), session.id()).memory();
        assertEquals(1, first.version());
        assertEquals(1, first.coveredTurns());
        assertTrue(first.content().toString().contains("张三"));
        assertTrue(lastSystem.get().contains("<conversation_memory>"));
        assertEquals(21, lastMessages.get().size());
        assertEquals("问题2", lastMessages.get().getFirst().content());
        service.send(agent.id(), session.id(), new SendRequest(UUID.randomUUID(), "继续"));
        var next = store.history(agent.id(), session.id()).memory();
        assertEquals(2, next.version());
        assertEquals(2, next.coveredTurns());
        assertTrue(
            Long.parseLong(next.coveredThroughSequence()) >
                Long.parseLong(first.coveredThroughSequence())
        );
        assertEquals(1, summaryInputs.get(0).path("olderTurns").size());
        assertTrue(summaryInputs.get(1).path("previousSummary").toString().contains("张三"));
        assertEquals("问题2", summaryInputs.get(1).path("olderTurns").get(0).path("user").asText());
        assertTrue(
            trace
                .events(agent.id(), firstRequest)
                .stream()
                .anyMatch(event -> event.kind().equals("SUMMARY_RESPONSE"))
        );
        var fresh = store.create(agent.id());
        assertEquals(0, store.history(agent.id(), fresh.id()).memory().version());
    }

    @Test
    void failedSummaryDoesNotErasePreviousMemoryOrBlockDialogue() {
        var session = store.create(agent.id());
        seed(session.id(), 11);
        when(
            client.completeMessages(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyList(),
                any(),
                any(java.time.Duration.class)
            )
        ).thenReturn(
            new OpenAiChatClient.Result(
                "{\"goal\":\"保留姓名\",\"confirmedFacts\":[\"用户叫张三\"]," +
                    "\"pendingQuestions\":[],\"references\":[]}",
                new Usage(20L, 5L, 25L)
            ),
            new OpenAiChatClient.Result("invalid-json", new Usage(7L, 3L, 10L))
        );
        service.send(agent.id(), session.id(), new SendRequest(UUID.randomUUID(), "先整理"));
        var previous = store.history(agent.id(), session.id()).memory();
        UUID request = UUID.randomUUID();
        var result = service.send(
            agent.id(),
            session.id(),
            new SendRequest(request, "再问一个问题")
        );
        var current = store.history(agent.id(), session.id()).memory();
        assertEquals(previous.version(), current.version());
        assertEquals(previous.coveredThroughSequence(), current.coveredThroughSequence());
        assertEquals(previous.content(), current.content());
        assertEquals(25L, result.answer().usage().totalTokens());
        assertTrue(
            trace
                .events(agent.id(), request)
                .stream()
                .anyMatch(event -> event.kind().equals("SUMMARY_FAILED"))
        );
        assertFalse(store.history(agent.id(), session.id()).session().generating());
    }

    @Test
    void ownershipRejectsOtherUsersIncludingAdministrators() {
        var session = store.create(agent.id());
        UUID request = UUID.randomUUID();
        service.send(agent.id(), session.id(), new SendRequest(request, "私有内容"));
        var other = users.create(
            new com.febridge.user.UserDtos.CreateUser(
                "other_" + UUID.randomUUID().toString().substring(0, 20),
                "另一位管理员",
                "Test-password-12345",
                com.febridge.user.UserDtos.Role.ADMIN
            )
        );
        authenticate(other.id());
        assertTrue(store.list(agent.id()).isEmpty());
        var denied = assertThrows(ResponseStatusException.class, () ->
            store.history(agent.id(), session.id())
        );
        assertEquals(404, denied.getStatusCode().value());
        assertThrows(ResponseStatusException.class, () ->
            service.send(agent.id(), session.id(), new SendRequest(UUID.randomUUID(), "越权"))
        );
        assertThrows(ResponseStatusException.class, () -> trace.requireAccess(agent.id(), request));
    }

    @Test
    void staleSummaryCannotOverwriteAfterLeaseExpiry() {
        var session = store.create(agent.id());
        seed(session.id(), 11);
        UUID request = UUID.randomUUID();
        var begin = store.begin(agent.id(), session.id(), new SendRequest(request, "测试"));
        var previous = memoryStore.plan(session.id(), begin.summarizeThrough()).previous();
        jdbc.update(
            "UPDATE chat_session SET locked_until=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=?",
            session.id()
        );
        assertFalse(
            memoryStore.save(
                session.id(),
                request,
                previous,
                json.readTree(
                    "{\"goal\":\"不能保存\",\"confirmedFacts\":[],\"pendingQuestions\":[],\"references\":[]}"
                ),
                begin.summarizeThrough(),
                1,
                false,
                "test-model",
                new Usage(1L, 1L, 2L),
                10
            )
        );
        assertEquals(0, memoryStore.view(session.id(), begin.summarizeThrough()).version());
    }

    private void seed(UUID sessionId, int count) {
        for (int index = 1; index <= count; index++) {
            jdbc.update(
                "INSERT INTO chat_turn(id,session_id,user_content,assistant_content,status," +
                    "model_name,provider_name,prompt_tokens,completion_tokens,total_tokens,duration_millis)" +
                    "VALUES(?,?,?,?,'completed','test-model','test-provider',1,1,2,10)",
                UUID.randomUUID(),
                sessionId,
                index == 1 ? "我叫张三" : "问题" + index,
                "回答" + index
            );
        }
    }

    private void authenticate(UUID id) {
        var user = com.febridge.security.SessionUser.from(users.find(id));
        var auth =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                user,
                null,
                java.util.List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_" + user.role()
                    )
                )
            );
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            auth
        );
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Autowired
    com.febridge.memory.LongMemoryStore longMemory;

    @Autowired
    com.febridge.memory.LongMemoryExtractor extractor;

    @Test
    void longMemoryCrossesSessionsButNotUsersAndHonorsControls() {
        UUID owner = com.febridge.user.AuthController.current().id();
        var first = store.create(agent.id());
        UUID request = UUID.randomUUID();
        String statement = "以后默认查询苏州工厂";
        service.send(agent.id(), first.id(), new SendRequest(request, statement));
        var job = takeMemoryJob(request);
        longMemory.finish(job, extractor.extract(statement), null);
        assertTrue(longMemory.recall(owner, agent.id(), "查询调拨记录").contains("苏州工厂"));
        var fresh = store.create(agent.id());
        service.send(agent.id(), fresh.id(), new SendRequest(UUID.randomUUID(), "查询调拨记录"));
        assertTrue(lastSystem.get().contains("用户长期记忆"));
        assertTrue(lastSystem.get().contains("苏州工厂"));
        assertEquals("", longMemory.recall(UUID.randomUUID(), agent.id(), "查询调拨"));
        var otherAgent = agents.create(
            new CreateAgentRequest("其他助手", "", "测试", json.createObjectNode())
        );
        assertEquals("", longMemory.recall(owner, otherAgent.id(), "查询调拨"));
        longMemory.policy(owner, agent.id(), false);
        assertEquals("", longMemory.recall(owner, agent.id(), "查询调拨"));
        longMemory.policy(owner, agent.id(), true);
        assertTrue(longMemory.recall(owner, agent.id(), "查询调拨").contains("苏州"));
        var row = jdbc.queryForMap(
            "SELECT * FROM long_memory WHERE owner_id=? AND agent_id=?",
            owner,
            agent.id()
        );
        var id = UUID.fromString(row.get("id").toString());
        longMemory.edit(
            owner,
            agent.id(),
            id,
            new com.febridge.memory.LongMemoryDtos.Edit("默认查询杭州工厂", true, null, 1),
            false
        );
        assertThrows(ResponseStatusException.class, () ->
            longMemory.edit(
                owner,
                agent.id(),
                id,
                new com.febridge.memory.LongMemoryDtos.Edit("旧版本修改", true, null, 1),
                false
            )
        );
        UUID next = UUID.randomUUID();
        service.send(agent.id(), fresh.id(), new SendRequest(next, "以后默认查询南京工厂"));
        longMemory.finish(takeMemoryJob(next), extractor.extract("以后默认查询南京工厂"), null);
        assertTrue(longMemory.recall(owner, agent.id(), "查询调拨").contains("杭州"));
        longMemory.edit(
            owner,
            agent.id(),
            id,
            new com.febridge.memory.LongMemoryDtos.Edit("默认查询杭州工厂", false, null, 2),
            true
        );
        assertEquals("", longMemory.recall(owner, agent.id(), "查询调拨"));
        UUID third = UUID.randomUUID();
        service.send(agent.id(), fresh.id(), new SendRequest(third, statement));
        longMemory.finish(takeMemoryJob(third), extractor.extract(statement), null);
        assertEquals("", longMemory.recall(owner, agent.id(), "查询调拨"));
    }

    @Test
    void disabledPolicyInvalidatesQueuedJobsAndExpiredFactsAreNotRecalled() {
        UUID owner = com.febridge.user.AuthController.current().id();
        var session = store.create(agent.id());
        UUID request = UUID.randomUUID();
        service.send(agent.id(), session.id(), new SendRequest(request, "以后请用中文回答"));
        var job = takeMemoryJob(request);
        longMemory.policy(owner, agent.id(), false);
        longMemory.policy(owner, agent.id(), true);
        longMemory.finish(job, extractor.extract("以后请用中文回答"), null);
        assertEquals("", longMemory.recall(owner, agent.id(), "你好"));
        longMemory.create(
            owner,
            agent.id(),
            new com.febridge.memory.LongMemoryDtos.Input(
                "fact",
                "fact.test",
                "我负责注塑模具",
                true,
                null
            )
        );
        assertTrue(longMemory.recall(owner, agent.id(), "查询模具").contains("注塑模具"));
        assertEquals("", longMemory.recall(owner, agent.id(), "今天天气"));
        longMemory.create(
            owner,
            agent.id(),
            new com.febridge.memory.LongMemoryDtos.Input(
                "preference",
                "preference.expired",
                "已过期的偏好",
                true,
                java.time.OffsetDateTime.now().minusDays(1)
            )
        );
        assertFalse(longMemory.recall(owner, agent.id(), "你好").contains("已过期"));
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT total_tokens FROM long_memory_job WHERE request_id=?",
                Integer.class,
                request
            )
        );
    }

    private java.util.Map<String, Object> takeMemoryJob(UUID request) {
        jdbc.update(
            "UPDATE long_memory_job SET status='running',claim=?,attempts=1," +
                "leased_until=CURRENT_TIMESTAMP+INTERVAL '90 seconds' WHERE request_id=?",
            UUID.randomUUID(),
            request
        );
        return jdbc.queryForMap("SELECT * FROM long_memory_job WHERE request_id=?", request);
    }

    @Test
    void durableWorkerClaimsJobAndManagementApiReturnsNoContent() throws Exception {
        var session = store.create(agent.id());
        UUID request = UUID.randomUUID();
        service.send(agent.id(), session.id(), new SendRequest(request, "以后请用中文回答"));
        var worker = new com.febridge.memory.LongMemoryWorker(longMemory, extractor);
        worker.poll();
        assertEquals(
            "completed",
            jdbc.queryForObject(
                "SELECT status FROM long_memory_job WHERE request_id=?",
                String.class,
                request
            )
        );
        UUID owner = com.febridge.user.AuthController.current().id();
        assertTrue(longMemory.recall(owner, agent.id(), "你好").contains("中文"));

        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new com.febridge.memory.LongMemoryController(longMemory, agents)
        ).build();
        String path = "/api/agents/" + agent.id() + "/long-memory";
        var response = mvc
            .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()
            )
            .andReturn()
            .getResponse();
        var view = json.readTree(
            response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
        );
        assertTrue(view.path("enabled").asBoolean());
        assertEquals(
            "preference.response_language",
            view.path("items").get(0).path("memory_key").asText()
        );
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                path + "/policy"
            )
                .contentType("application/json")
                .content("{\"enabled\":false}")
        ).andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent()
        );
        assertEquals("", longMemory.recall(owner, agent.id(), "你好"));
    }

    @Test
    void memoryModesControlActualModelContextAndLongTermWrites() {
        UUID owner = com.febridge.user.AuthController.current().id();
        longMemory.create(
            owner,
            agent.id(),
            new com.febridge.memory.LongMemoryDtos.Input(
                "default",
                "default.factory",
                "默认查询苏州工厂",
                true,
                null
            )
        );
        var session = store.create(agent.id());
        longMemory.setMode(owner, agent.id(), com.febridge.memory.MemoryMode.NONE);
        UUID noneRequest = UUID.randomUUID();
        service.send(agent.id(), session.id(), new SendRequest(noneRequest, "我长期负责注塑模具"));
        service.send(agent.id(), session.id(), new SendRequest(UUID.randomUUID(), "我负责什么"));
        assertEquals(1, lastMessages.get().size());
        assertFalse(lastSystem.get().contains("苏州"));
        assertFalse(lastSystem.get().contains("当前会话摘要"));
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM long_memory_job WHERE request_id=?",
                Integer.class,
                noneRequest
            )
        );

        longMemory.setMode(owner, agent.id(), com.febridge.memory.MemoryMode.CONTEXT);
        service.send(agent.id(), session.id(), new SendRequest(UUID.randomUUID(), "继续"));
        assertTrue(lastMessages.get().size() > 1);
        assertFalse(lastSystem.get().contains("苏州"));
        longMemory.setMode(owner, agent.id(), com.febridge.memory.MemoryMode.CROSS_SESSION);
        service.send(
            agent.id(),
            store.create(agent.id()).id(),
            new SendRequest(UUID.randomUUID(), "查调拨")
        );
        assertEquals(1, lastMessages.get().size());
        assertTrue(lastSystem.get().contains("苏州"));
        var other = agents.create(
            new CreateAgentRequest("默认模式测试", "", "测试", json.createObjectNode())
        );
        assertEquals(com.febridge.memory.MemoryMode.CONTEXT, longMemory.mode(owner, other.id()));
        assertEquals("CONTEXT", longMemory.view(owner, other.id()).get("mode"));

        UUID pending = UUID.randomUUID();
        store.begin(agent.id(), session.id(), new SendRequest(pending, "处理中"));
        assertThrows(ResponseStatusException.class, () ->
            longMemory.setMode(owner, agent.id(), com.febridge.memory.MemoryMode.NONE)
        );
        store.fail(session.id(), pending, "测试结束");
    }

    @Test
    void memoryModeBelongsToAgentWhileMemoryContentsRemainPrivate() throws Exception {
        UUID owner = com.febridge.user.AuthController.current().id();
        UUID another = UUID.randomUUID();
        longMemory.setMode(owner, agent.id(), com.febridge.memory.MemoryMode.NONE);
        assertEquals(com.febridge.memory.MemoryMode.NONE, longMemory.mode(another, agent.id()));
        longMemory.setMode(another, agent.id(), com.febridge.memory.MemoryMode.CROSS_SESSION);
        assertEquals(
            com.febridge.memory.MemoryMode.CROSS_SESSION,
            longMemory.mode(owner, agent.id())
        );
        longMemory.create(
            owner,
            agent.id(),
            new com.febridge.memory.LongMemoryDtos.Input(
                "default",
                "default.factory",
                "默认苏州工厂",
                true,
                null
            )
        );
        assertTrue(longMemory.recall(owner, agent.id(), "查询").contains("苏州"));
        assertEquals("", longMemory.recall(another, agent.id(), "查询"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new com.febridge.memory.MemoryModeController(longMemory, agents)
        ).build();
        String path = "/api/agents/" + agent.id() + "/memory-mode";
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .contentType("application/json")
                .content("{\"mode\":\"CONTEXT\"}")
        ).andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent()
        );
        var response = mvc
            .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()
            )
            .andReturn()
            .getResponse();
        assertEquals("CONTEXT", json.readTree(response.getContentAsString()).path("mode").asText());
        assertEquals(com.febridge.memory.MemoryMode.CONTEXT, longMemory.mode(another, agent.id()));
    }
}
