package com.febridge.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.febridge.agent.*;
import com.febridge.chat.*;
import com.febridge.model.*;
import com.febridge.model.ModelDtos.*;
import com.febridge.security.SessionUser;
import com.febridge.user.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
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
    properties = {
        "platform.auth.bootstrap-enabled=false",
        "platform.knowledge.worker-enabled=false",
        "platform.memory.worker-enabled=false",
    }
)
@Transactional
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DB_URL", matches = ".+")
class KnowledgePersistenceTest {

    @Autowired
    KnowledgeStore store;

    @Autowired
    KnowledgeSearch search;

    @Autowired
    UserService users;

    @Autowired
    AgentService agents;

    @Autowired
    ModelRegistry models;

    @Autowired
    AgentChatService chat;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @MockitoBean
    KnowledgeClients clients;

    @MockitoBean
    OpenAiChatClient llm;

    UUID owner;
    UUID other;
    List<UUID> indexed = new ArrayList<>();

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
        owner = user();
        other = user();
        authenticate(owner);
        when(clients.space()).thenReturn("test-v1");
        when(clients.embed(anyList(), any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts
                .stream()
                .map(text -> List.of(1.0, 0.0))
                .toList();
        });
        doAnswer(invocation -> {
            List<UUID> ids = invocation.getArgument(2);
            indexed.addAll(ids);
            return null;
        })
            .when(clients)
            .upsert(any(), any(), anyList(), anyList());
        when(clients.query(anyList(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            var result = json.createArrayNode();
            for (UUID id : indexed) {
                result.addObject().put("id", id.toString()).put("score", 0.9);
            }
            return result;
        });
    }

    private UUID user() {
        return users
            .create(
                new UserDtos.CreateUser(
                    "kb_" + UUID.randomUUID().toString().substring(0, 20),
                    "知识库测试",
                    "Test-password-12345",
                    UserDtos.Role.USER
                )
            )
            .id();
    }

    private void authenticate(UUID id) {
        var principal = SessionUser.from(users.find(id));
        var auth =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_USER"
                    )
                )
            );
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            auth
        );
    }

    private UUID base(String visibility) {
        return UUID.fromString(
            store
                .create(owner, new KnowledgeDtos.BaseInput("调拨规范", "用于测试", visibility))
                .get("id")
                .toString()
        );
    }

    private UUID upload(UUID kb) {
        return store.upload(
            owner,
            kb,
            new KnowledgeDtos.DocumentInput(
                "调拨规范.md",
                "# 调拨审批\n模具跨工厂调拨必须经过调出工厂和调入工厂审批。"
            ),
            clients.space()
        );
    }

    private void work() {
        var job = store.claim();
        assertNotNull(job);
        new KnowledgeWorker(store, clients).process(job);
    }

    @Test
    void indexesRetrievesCitationsAndDeletesWithoutReturningStaleVectors() {
        UUID kb = base("PRIVATE");
        assertTrue(
            search.search(owner, List.of(kb.toString()), "调拨", 5, Duration.ofSeconds(5)).isEmpty()
        );
        UUID document = upload(kb);
        work();
        assertEquals("ready", store.documents(owner, kb).getFirst().get("status"));
        var hits = search.search(
            owner,
            List.of(kb.toString()),
            "调拨审批",
            5,
            Duration.ofSeconds(5)
        );
        assertEquals(1, hits.size());
        assertEquals(document, hits.getFirst().documentId());
        assertTrue(hits.getFirst().citation().contains("调拨规范.md"));
        assertTrue(hits.getFirst().content().contains("调入工厂"));
        store.action(owner, kb, document, true);
        assertTrue(
            search.search(owner, List.of(kb.toString()), "调拨", 5, Duration.ofSeconds(5)).isEmpty()
        );
        work();
        verify(clients).delete(document);
        assertTrue(store.documents(owner, kb).isEmpty());
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunk WHERE document_id=?",
                Integer.class,
                document
            )
        );
    }

    @Test
    void filtersPrivateKnowledgeBeforeSearchAndRechecksReturnedChunkPermissions() {
        UUID privateBase = base("PRIVATE");
        UUID sharedBase = base("SHARED");
        upload(privateBase);
        work();
        upload(sharedBase);
        work();
        assertThrows(ResponseStatusException.class, () -> store.documents(other, privateBase));
        assertThrows(ResponseStatusException.class, () ->
            search.search(other, List.of(privateBase.toString()), "调拨", 5, Duration.ofSeconds(5))
        );
        assertThrows(ResponseStatusException.class, () ->
            store.upload(
                other,
                sharedBase,
                new KnowledgeDtos.DocumentInput("x.txt", "测试"),
                clients.space()
            )
        );
        var hits = search.search(
            other,
            List.of(sharedBase.toString()),
            "调拨",
            5,
            Duration.ofSeconds(5)
        );
        assertEquals(1, hits.size());
        assertEquals(sharedBase, hits.getFirst().knowledgeBaseId());
        verify(clients).query(eq(List.of(sharedBase.toString())), anyList(), anyInt(), any());
        var agent = agents.create(
            new CreateAgentRequest("知识测试", "", "测试", json.createObjectNode())
        );
        store.bind(owner, agent.id(), List.of(privateBase, sharedBase));
        assertEquals(1, store.bound(other, agent.id()).size());
    }

    @Test
    void handlesFailureRetryAndDeletionFailureWithoutResurrectingDocuments() {
        UUID kb = base("PRIVATE");
        UUID document = upload(kb);
        when(clients.embed(anyList(), any())).thenThrow(new IllegalStateException("test"));
        work();
        assertEquals("failed", store.documents(owner, kb).getFirst().get("status"));
        store.action(owner, kb, document, false);
        doReturn(List.of(List.of(1.0, 0.0)))
            .when(clients)
            .embed(anyList(), any());
        work();
        assertEquals("ready", store.documents(owner, kb).getFirst().get("status"));
        store.action(owner, kb, document, true);
        doThrow(new IllegalStateException("test")).when(clients).delete(document);
        work();
        assertEquals("delete_failed", store.documents(owner, kb).getFirst().get("status"));
        assertTrue(
            search.search(owner, List.of(kb.toString()), "调拨", 5, Duration.ofSeconds(5)).isEmpty()
        );
        store.action(owner, kb, document, false);
        doNothing().when(clients).delete(document);
        work();
        assertTrue(store.documents(owner, kb).isEmpty());
    }

    @Test
    void agentUsesKnowledgeToolAndIncludesCitationsInTraceWithoutCallingBusinessMcp() {
        UUID kb = base("PRIVATE");
        upload(kb);
        work();
        var provider = models.createProvider(
            new ProviderInput("知识测试", ProviderType.DEEPSEEK, "dummy-test-key", true)
        );
        var model = models.createModel(
            new ModelInput(provider.id(), "test-model", "测试", true, json.createObjectNode(), true)
        );
        var agent = agents.create(
            new CreateAgentRequest(
                "知识助手",
                "",
                "你是知识助手",
                json.createObjectNode(),
                model.id()
            )
        );
        store.bind(owner, agent.id(), List.of(kb));
        var assistant = json.createObjectNode().put("role", "assistant");
        var calls = assistant.putArray("tool_calls");
        var call = calls.addObject().put("id", "knowledge-call").put("type", "function");
        call.putObject("function")
            .put("name", "search_knowledge")
            .put("arguments", "{\"query\":\"调拨审批\"}");
        when(
            llm.completeTurn(
                anyString(),
                anyString(),
                anyString(),
                anyList(),
                any(),
                anyList(),
                any()
            )
        )
            .thenReturn(
                new OpenAiChatClient.Result(
                    "",
                    new ChatDtos.Usage(1L, 1L, 2L),
                    assistant,
                    List.of(
                        new OpenAiChatClient.ToolCall(
                            "knowledge-call",
                            "search_knowledge",
                            "{\"query\":\"调拨审批\"}"
                        )
                    )
                )
            )
            .thenReturn(
                new OpenAiChatClient.Result(
                    "需要双方审批。[调拨规范.md / 片段1]",
                    new ChatDtos.Usage(1L, 1L, 2L)
                )
            );
        UUID request = UUID.randomUUID();
        var answer = chat.chatMessages(
            agent.id(),
            List.of(new OpenAiChatClient.PromptMessage("user", "调拨如何审批")),
            request
        );
        assertTrue(answer.content().contains("调拨规范"));
        assertEquals(4L, answer.usage().totalTokens());
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run_event WHERE request_id=? AND kind='TOOL_RESULT'",
                Integer.class,
                request
            )
        );
        assertTrue(
            search
                .execute(
                    agent.id(),
                    "{\"query\":\"调拨\",\"limit\":1000}",
                    store.bound(owner, agent.id()),
                    5
                )
                .error()
        );
    }

    @AfterEach
    void cleanup() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
}
