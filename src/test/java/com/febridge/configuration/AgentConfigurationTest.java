package com.febridge.configuration;

import static com.febridge.configuration.ConfigurationDtos.*;
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
import com.febridge.tool.ToolRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "platform.auth.bootstrap-enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DB_URL", matches = ".+")
class AgentConfigurationTest {

    @Autowired
    AgentConfiguration configuration;

    @Autowired
    AgentService agents;

    @Autowired
    AgentChatService chat;

    @Autowired
    ModelRegistry models;

    @Autowired
    ToolRegistry tools;

    @Autowired
    JsonMapper json;

    @MockitoBean
    OpenAiChatClient client;

    @MockitoBean
    McpConnector connector;

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

    private AgentDefinition agent() {
        var provider = models.createProvider(
            new ProviderInput("配置测试", ProviderType.DEEPSEEK, "dummy", true)
        );
        var model = models.createModel(
            new ModelInput(
                provider.id(),
                "config-test",
                "配置测试",
                true,
                json.createObjectNode(),
                true
            )
        );
        return agents.create(
            new CreateAgentRequest("配置助手", "", "原提示词", json.createObjectNode(), model.id())
        );
    }

    private ToolView tool(UUID agentId) {
        var server = tools.save(
            null,
            new ServerInput("配置测试 MCP", "http://127.0.0.1:8090/mcp", "", true, 5, 1024)
        );
        var definition = json.readValue(
            """
            {"name":"query_customer","description":"只读查询","inputSchema":{"type":"object","properties":{}}}
            """,
            McpSchema.Tool.class
        );
        when(connector.discover(anyMap())).thenReturn(List.of(definition));
        var tool = tools.sync(server.id()).getFirst();
        tools.updatePolicy(tool.id(), new ToolPolicy(true, true));
        tools.bindServers(agentId, new ServerBinding(List.of(tool.serverId())));
        return tool;
    }

    private OpenAiChatClient.Result call(ToolView tool, String id) {
        var assistant = json.createObjectNode().put("role", "assistant");
        var function = json.createObjectNode().put("name", tool.alias()).put("arguments", "{}");
        assistant.set(
            "tool_calls",
            json
                .createArrayNode()
                .add(
                    json
                        .createObjectNode()
                        .put("id", id)
                        .put("type", "function")
                        .set("function", function)
                )
        );
        return new OpenAiChatClient.Result(
            "",
            new Usage(1L, 1L, 2L),
            assistant,
            List.of(new OpenAiChatClient.ToolCall(id, tool.alias(), "{}"))
        );
    }

    @Test
    void editsPromptAndUsesBoundSkillsInOrderWithLiveDisable() {
        var agent = agent();
        var first = configuration.saveSkill(
            null,
            new SkillInput("第一步", "", "先确认客户编号", true)
        );
        var second = configuration.saveSkill(
            null,
            new SkillInput("第二步", "", "按查询结果回答", true)
        );
        configuration.bind(agent.id(), new SkillBinding(List.of(second.id(), first.id())));
        var updated = configuration.profile(
            agent.id(),
            new Profile("新名字", null, "新系统提示词")
        );
        assertEquals(agent.modelId(), updated.modelId());
        assertEquals("新名字", updated.name());
        assertEquals("", updated.description());
        String prompt = configuration.runtime(agent.id()).systemPrompt();
        assertTrue(prompt.startsWith("新系统提示词"));
        assertTrue(prompt.indexOf("按查询结果回答") < prompt.indexOf("先确认客户编号"));
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
            assertEquals(
                configuration.runtime(agent.id()).systemPrompt(),
                invocation.getArgument(3)
            );
            return new OpenAiChatClient.Result("回答", new Usage(1L, 1L, 2L));
        });
        chat.chat(agent.id(), new ChatDtos.Request("你好"));
        configuration.saveSkill(first.id(), new SkillInput("第一步", "", "停用后不应出现", false));
        assertFalse(configuration.runtime(agent.id()).systemPrompt().contains("停用后不应出现"));
        assertThrows(ResponseStatusException.class, () ->
            configuration.bind(agent.id(), new SkillBinding(List.of(first.id())))
        );
    }

    @Test
    void rejectsInvalidRulesAndTooManyBindings() {
        var agent = agent();
        assertEquals(Rules.defaults(), configuration.rules(agent.id()));
        assertThrows(ConstraintViolationException.class, () ->
            configuration.saveRules(
                agent.id(),
                new Rules(11, 12, 170, 30, true, FailurePolicy.CONTINUE)
            )
        );
        assertThrows(ConstraintViolationException.class, () ->
            configuration.saveRules(
                agent.id(),
                new Rules(6, 21, 170, 30, true, FailurePolicy.CONTINUE)
            )
        );
        assertThrows(ConstraintViolationException.class, () ->
            configuration.saveRules(agent.id(), new Rules(6, 12, 171, 30, true, null))
        );
        assertThrows(ConstraintViolationException.class, () ->
            configuration.bind(
                agent.id(),
                new SkillBinding(java.util.Collections.nCopies(9, UUID.randomUUID()))
            )
        );
    }

    @Test
    void disabledToolsAndZeroBudgetNeverAdvertiseOrExecuteTools() {
        var agent = agent();
        tool(agent.id());
        when(
            client.completeMessages(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyList(),
                any()
            )
        ).thenReturn(new OpenAiChatClient.Result("普通回答", new Usage(1L, 1L, 2L)));
        configuration.saveRules(
            agent.id(),
            new Rules(6, 12, 170, 30, false, FailurePolicy.CONTINUE)
        );
        chat.chat(agent.id(), new ChatDtos.Request("查客户"));
        configuration.saveRules(agent.id(), new Rules(6, 0, 170, 30, true, FailurePolicy.CONTINUE));
        chat.chat(agent.id(), new ChatDtos.Request("查客户"));
        verify(client, never()).completeTurn(
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            any(),
            anyList(),
            any()
        );
        verify(connector, never()).call(anyMap(), anyString(), anyMap());
    }

    @Test
    void enforcesModelAndToolBudgetsBeforeExtraExecution() {
        var agent = agent();
        var tool = tool(agent.id());
        configuration.saveRules(agent.id(), new Rules(2, 1, 170, 3, true, FailurePolicy.CONTINUE));
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
            .thenReturn(call(tool, "first"))
            .thenReturn(call(tool, "second"));
        when(connector.call(anyMap(), anyString(), anyMap())).thenReturn(
            new ToolResult("结果", false)
        );
        assertThrows(ResponseStatusException.class, () ->
            chat.chat(agent.id(), new ChatDtos.Request("查客户"))
        );
        verify(client, times(2)).completeTurn(
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            any(),
            anyList(),
            any()
        );
        verify(connector, times(1)).call(anyMap(), anyString(), anyMap());
    }

    @Test
    void stopPolicyTerminatesAfterToolFailure() {
        var agent = agent();
        var tool = tool(agent.id());
        configuration.saveRules(agent.id(), new Rules(6, 12, 170, 3, true, FailurePolicy.STOP));
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
        ).thenReturn(call(tool, "failure"));
        when(connector.call(anyMap(), anyString(), anyMap())).thenReturn(
            new ToolResult("失败", true)
        );
        var failure = assertThrows(ResponseStatusException.class, () ->
            chat.chat(agent.id(), new ChatDtos.Request("查客户"))
        );
        assertTrue(failure.getReason().contains("执行规则终止"));
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
    void customTimeoutAppliesToOrdinaryChatToo() {
        var agent = agent();
        configuration.saveRules(agent.id(), new Rules(1, 0, 10, 3, false, FailurePolicy.CONTINUE));
        when(
            client.completeMessages(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyList(),
                any(),
                eq(Duration.ofSeconds(10))
            )
        ).thenReturn(new OpenAiChatClient.Result("回答", new Usage(1L, 1L, 2L)));
        assertEquals("回答", chat.chat(agent.id(), new ChatDtos.Request("你好")).content());
        verify(client).completeMessages(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            any(),
            eq(Duration.ofSeconds(10))
        );
    }
}
