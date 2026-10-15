package com.febridge.tool;

import static com.febridge.tool.ToolDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.febridge.agent.*;
import com.febridge.chat.*;
import com.febridge.chat.ChatDtos.Usage;
import com.febridge.mcp.McpConnector;
import com.febridge.model.*;
import com.febridge.model.ModelDtos.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "platform.auth.bootstrap-enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DB_URL", matches = ".+")
class ToolPersistenceTest {

    @Autowired
    ToolRegistry registry;

    @Autowired
    ToolExecutor executor;

    @Autowired
    AgentService agents;

    @Autowired
    AgentChatService chat;

    @Autowired
    ModelRegistry models;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    McpConnector connector;

    @MockitoBean
    OpenAiChatClient client;

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

    @Autowired
    com.febridge.trace.RunTrace trace;

    private ToolView createTool() {
        var server = registry.save(
            null,
            new ServerInput("测试 MCP", "http://127.0.0.1:8090/mcp", "test-token", true, 5, 1024)
        );
        assertTrue(server.tokenConfigured());
        String encrypted = jdbc.queryForObject(
            "SELECT token_ciphertext FROM mcp_server WHERE id=?",
            String.class,
            server.id()
        );
        assertNotEquals("test-token", encrypted);
        var definition = json.readValue(
            """
            {"name":"query_customer","description":"只读客户查询","inputSchema":
            {"type":"object","properties":{"customerId":{"type":"string"}},
            "required":["customerId"],"additionalProperties":false}}
            """,
            McpSchema.Tool.class
        );
        when(connector.discover(anyMap())).thenReturn(List.of(definition));
        var tool = registry.sync(server.id()).getFirst();
        assertTrue(tool.enabled());
        assertFalse(tool.readOnly());
        return tool;
    }

    @Test
    void enforcesAuthorizationValidationAndRevocationWithAudits() {
        var agent = agents.create(
            new CreateAgentRequest("工具测试", "", "测试", json.createObjectNode())
        );
        var tool = createTool();
        registry.updatePolicy(tool.id(), new ToolPolicy(false));
        registry.bindServers(agent.id(), new ServerBinding(List.of(tool.serverId())));
        assertTrue(registry.available(agent.id()).isEmpty());
        registry.updatePolicy(tool.id(), new ToolPolicy(true));
        registry.bindServers(agent.id(), new ServerBinding(List.of(tool.serverId())));
        UUID request = UUID.randomUUID();
        when(connector.call(anyMap(), eq("query_customer"), anyMap())).thenReturn(
            new ToolResult("C001", false)
        );
        assertTrue(executor.execute(agent.id(), request, "invalid", tool.alias(), "{}", 5).error());
        verify(connector, never()).call(anyMap(), anyString(), anyMap());
        assertFalse(
            executor
                .execute(agent.id(), request, "valid", tool.alias(), "{\"customerId\":\"C001\"}", 5)
                .error()
        );
        var audits = registry.audits(agent.id(), request);
        assertEquals(2, audits.size());
        assertTrue(
            audits
                .stream()
                .anyMatch(
                    a ->
                        a.status().equals("completed") &&
                        a.argumentFields().toString().equals("[\"customerId\"]")
                )
        );
        registry.updatePolicy(tool.id(), new ToolPolicy(true, false));
        assertTrue(
            executor
                .execute(
                    agent.id(),
                    request,
                    "revoked",
                    tool.alias(),
                    "{\"customerId\":\"C001\"}",
                    5
                )
                .error()
        );
        verify(connector, times(1)).call(anyMap(), anyString(), anyMap());
        assertTrue(
            registry
                .audits(agent.id(), request)
                .stream()
                .anyMatch(a -> a.status().equals("denied"))
        );
    }

    @Test
    void runsModelToolModelLoopAndAggregatesUsage() {
        var provider = models.createProvider(
            new ProviderInput("工具测试提供商", ProviderType.DEEPSEEK, "dummy", true)
        );
        var model = models.createModel(
            new ModelInput(provider.id(), "test-tools", "测试", true, json.createObjectNode(), true)
        );
        var agent = agents.create(
            new CreateAgentRequest(
                "循环测试",
                "",
                "按编号查客户",
                json.createObjectNode(),
                model.id()
            )
        );
        var tool = createTool();
        registry.updatePolicy(tool.id(), new ToolPolicy(true));
        registry.bindServers(agent.id(), new ServerBinding(List.of(tool.serverId())));
        when(connector.call(anyMap(), eq("query_customer"), anyMap())).thenReturn(
            new ToolResult("客户 C001 是 VIP", false)
        );
        var assistant = json.createObjectNode().put("role", "assistant");
        assistant.set(
            "tool_calls",
            json.readTree(
                "[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"" +
                    tool.alias() +
                    "\",\"arguments\":\"{\\\"customerId\\\":\\\"C001\\\"}\"}}]"
            )
        );
        var call = new OpenAiChatClient.ToolCall(
            "call1",
            tool.alias(),
            "{\"customerId\":\"C001\"}"
        );
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
        )
            .thenReturn(
                new OpenAiChatClient.Result("", new Usage(10L, 2L, 12L), assistant, List.of(call))
            )
            .thenAnswer(invocation -> {
                List<JsonNode> messages = invocation.getArgument(3);
                assertEquals("tool", messages.getLast().path("role").asText());
                assertEquals("call1", messages.getLast().path("tool_call_id").asText());
                assertTrue(messages.getLast().path("content").asText().contains("VIP"));
                return new OpenAiChatClient.Result("客户是 VIP", new Usage(20L, 3L, 23L));
            });
        UUID request = UUID.randomUUID();
        var answer = chat.chatMessages(
            agent.id(),
            List.of(new OpenAiChatClient.PromptMessage("user", "查 C001")),
            request
        );
        assertEquals("客户是 VIP", answer.content());
        assertEquals(35L, answer.usage().totalTokens());
        assertEquals(
            List.of(
                "RUN_STARTED",
                "CONTEXT_PREPARED",
                "MODEL_REQUEST",
                "MODEL_RESPONSE",
                "TOOL_REQUEST",
                "TOOL_RESULT",
                "MODEL_REQUEST",
                "MODEL_RESPONSE",
                "RUN_COMPLETED"
            ),
            trace
                .events(agent.id(), request)
                .stream()
                .map(com.febridge.trace.RunTrace.Event::kind)
                .toList()
        );
        assertTrue(
            trace
                .events(agent.id(), request)
                .stream()
                .filter(event -> event.kind().equals("TOOL_RESULT"))
                .findFirst()
                .orElseThrow()
                .details()
                .toString()
                .contains("VIP")
        );
        var requests = trace
            .events(agent.id(), request)
            .stream()
            .filter(event -> event.kind().equals("MODEL_REQUEST"))
            .toList();
        assertEquals(1, requests.getFirst().details().path("step").asInt());
        assertEquals(2, requests.getLast().details().path("step").asInt());
        assertTrue(requests.getLast().details().path("messages").toString().contains("VIP"));
        assertFalse(requests.getLast().details().toString().contains("test-token"));
        assertEquals("completed", registry.audits(agent.id(), request).getFirst().status());
        verify(connector, times(1)).call(anyMap(), anyString(), anyMap());
        verify(client, times(2)).completeTurn(
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
    void savesCustomHeaderEncryptedAndPreservesOrClearsCredentials() {
        var input = new ServerInput(
            "自定义鉴权测试",
            "http://127.0.0.1:8090/mcp",
            "test-custom-secret",
            true,
            5,
            1024,
            com.febridge.mcp.McpAuthentication.Type.CUSTOM_HEADER,
            "X-API-Key"
        );
        var server = registry.save(null, input);
        assertEquals("X-API-Key", server.authHeader());
        assertTrue(server.tokenConfigured());
        assertFalse(json.writeValueAsString(server).contains("test-custom-secret"));
        String encrypted = jdbc.queryForObject(
            "SELECT token_ciphertext FROM mcp_server WHERE id=?",
            String.class,
            server.id()
        );
        assertNotEquals("test-custom-secret", encrypted);
        registry.save(
            server.id(),
            new ServerInput(
                "保留凭证",
                server.endpoint(),
                "",
                true,
                5,
                1024,
                com.febridge.mcp.McpAuthentication.Type.CUSTOM_HEADER,
                "X-API-Key"
            )
        );
        assertEquals(
            encrypted,
            jdbc.queryForObject(
                "SELECT token_ciphertext FROM mcp_server WHERE id=?",
                String.class,
                server.id()
            )
        );
        assertThrows(ResponseStatusException.class, () ->
            registry.save(
                server.id(),
                new ServerInput(
                    "错误 Key",
                    server.endpoint(),
                    "",
                    true,
                    5,
                    1024,
                    com.febridge.mcp.McpAuthentication.Type.CUSTOM_HEADER,
                    "Host"
                )
            )
        );
        assertThrows(ResponseStatusException.class, () ->
            registry.save(
                server.id(),
                new ServerInput(
                    "改变 Key",
                    server.endpoint(),
                    "",
                    true,
                    5,
                    1024,
                    com.febridge.mcp.McpAuthentication.Type.CUSTOM_HEADER,
                    "api-key"
                )
            )
        );
        var cleared = registry.save(
            server.id(),
            new ServerInput(
                "清除凭证",
                server.endpoint(),
                "",
                true,
                5,
                1024,
                com.febridge.mcp.McpAuthentication.Type.NONE,
                null
            )
        );
        assertFalse(cleared.tokenConfigured());
        assertNull(
            jdbc.queryForObject(
                "SELECT token_ciphertext FROM mcp_server WHERE id=?",
                String.class,
                server.id()
            )
        );
    }

    @Test
    void discoveryPreservesManualChoicesAndDisablesMissingTools() {
        var tool = createTool();
        assertTrue(registry.sync(tool.serverId()).getFirst().enabled());
        registry.updatePolicy(tool.id(), new ToolPolicy(false));
        var changed = json.readValue(
            """
            {"name":"query_customer","description":"更新后的工具定义","inputSchema":
            {"type":"object","properties":{"customerId":{"type":"string"}},
            "required":["customerId"],"additionalProperties":false}}
            """,
            McpSchema.Tool.class
        );
        when(connector.discover(anyMap())).thenReturn(List.of(changed));
        assertFalse(registry.sync(tool.serverId()).getFirst().enabled());
        registry.updatePolicy(tool.id(), new ToolPolicy(true));
        assertTrue(registry.sync(tool.serverId()).getFirst().enabled());
        when(connector.discover(anyMap())).thenReturn(List.of());
        assertFalse(registry.sync(tool.serverId()).getFirst().enabled());
        assertThrows(ResponseStatusException.class, () ->
            registry.updatePolicy(tool.id(), new ToolPolicy(true))
        );
        when(connector.discover(anyMap())).thenReturn(List.of(changed));
        assertTrue(registry.sync(tool.serverId()).getFirst().enabled());
    }

    @Test
    void serverBindingIncludesNewToolsAndHonorsDisableAndUnbinding() {
        var agent = agents.create(
            new CreateAgentRequest("MCP 绑定测试", "", "测试", json.createObjectNode())
        );
        var first = createTool();
        registry.bindServers(agent.id(), new ServerBinding(List.of(first.serverId())));
        assertEquals(1, registry.available(agent.id()).size());
        var original = json.readValue(
            """
            {"name":"query_customer","description":"只读客户查询","inputSchema":
            {"type":"object","properties":{"customerId":{"type":"string"}},
            "required":["customerId"],"additionalProperties":false}}
            """,
            McpSchema.Tool.class
        );
        var added = json.readValue(
            """
            {"name":"new_business_tool","description":"新发现的工具","inputSchema":{"type":"object","properties":{}}}
            """,
            McpSchema.Tool.class
        );
        when(connector.discover(anyMap())).thenReturn(List.of(original, added));
        var discovered = registry.sync(first.serverId());
        assertEquals(2, registry.available(agent.id()).size());
        var second = discovered
            .stream()
            .filter(tool -> tool.name().equals("new_business_tool"))
            .findFirst()
            .orElseThrow();
        assertEquals(second.id(), registry.authorized(agent.id(), second.alias()).id());
        registry.updatePolicy(second.id(), new ToolPolicy(false));
        assertEquals(1, registry.available(agent.id()).size());
        assertThrows(ResponseStatusException.class, () ->
            registry.authorized(agent.id(), second.alias())
        );
        var server = registry.boundServers(agent.id()).getFirst();
        registry.save(
            server.id(),
            new ServerInput(
                server.name(),
                server.endpoint(),
                "",
                false,
                server.timeoutSeconds(),
                server.resultLimit(),
                server.authType(),
                server.authHeader()
            )
        );
        assertTrue(registry.available(agent.id()).isEmpty());
        assertEquals(1, registry.boundServers(agent.id()).size());
        registry.bindServers(agent.id(), new ServerBinding(List.of()));
        assertTrue(registry.boundServers(agent.id()).isEmpty());
        assertThrows(ResponseStatusException.class, () ->
            registry.authorized(agent.id(), first.alias())
        );
    }

    @Autowired
    com.febridge.user.UserService testUsers;

    @org.junit.jupiter.api.BeforeEach
    void authenticateTestUser() {
        var user = testUsers.create(
            new com.febridge.user.UserDtos.CreateUser(
                "kb_reg_" + UUID.randomUUID().toString().substring(0, 18),
                "回归测试用户",
                "Test-password-12345",
                com.febridge.user.UserDtos.Role.USER
            )
        );
        var principal = com.febridge.security.SessionUser.from(testUsers.find(user.id()));
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

    @org.junit.jupiter.api.AfterEach
    void clearTestAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
}
