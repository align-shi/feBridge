package com.febridge.version;

import static org.junit.jupiter.api.Assertions.*;

import com.febridge.agent.*;
import com.febridge.configuration.AgentConfiguration;
import com.febridge.configuration.ConfigurationDtos.*;
import com.febridge.knowledge.*;
import com.febridge.memory.*;
import com.febridge.model.*;
import com.febridge.model.ModelDtos.*;
import com.febridge.security.SessionUser;
import com.febridge.tool.*;
import com.febridge.tool.ToolDtos.*;
import com.febridge.user.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
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
class AgentVersionsTest {

    @Autowired
    AgentVersions versions;

    @Autowired
    AgentService agents;

    @Autowired
    AgentConfiguration config;

    @Autowired
    ModelRegistry models;

    @Autowired
    ToolRegistry tools;

    @Autowired
    KnowledgeStore knowledge;

    @Autowired
    LongMemoryStore memory;

    @Autowired
    UserService users;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    AgentDefinition agent;
    UUID owner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv("PLATFORM_TEST_DB_URL"));
        p.add("spring.datasource.username", () -> System.getenv("PLATFORM_TEST_DB_USERNAME"));
        p.add("spring.datasource.password", () -> System.getenv("PLATFORM_TEST_DB_PASSWORD"));
    }

    UUID user() {
        return users
            .create(
                new UserDtos.CreateUser(
                    "ver_" + UUID.randomUUID().toString().substring(0, 20),
                    "版本测试用户",
                    "Test-password-12345",
                    UserDtos.Role.USER
                )
            )
            .id();
    }

    void authenticate(UUID id) {
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

    @BeforeEach
    void setup() {
        owner = user();
        authenticate(owner);
        var provider = models.createProvider(
            new ProviderInput("版本测试", ProviderType.DEEPSEEK, "dummy-version-key", true)
        );
        var model = models.createModel(
            new ModelInput(
                provider.id(),
                "version-test",
                "测试模型",
                true,
                json.createObjectNode(),
                true
            )
        );
        agent = agents.create(
            new CreateAgentRequest(
                "版本助手",
                "版本测试",
                "第一版提示词",
                json.createObjectNode(),
                model.id()
            )
        );
    }

    VersionDtos.State publish(String note) {
        return versions.publish(
            agent.id(),
            new VersionDtos.Publish(note, versions.state(agent.id()).fingerprint())
        );
    }

    @Test
    void publishesImmutableSnapshotsAndSwitchesActiveWithoutChangingDraft() {
        var first = publish("首次发布");
        UUID v1 = first.activeVersionId();
        assertFalse(first.changed());
        assertEquals(1, first.versions().getFirst().number());
        config.profile(agent.id(), new Profile("版本助手", "版本测试", "第二版提示词"));
        assertTrue(versions.state(agent.id()).changed());
        var detail = versions.detail(agent.id(), v1);
        var snapshot = (tools.jackson.databind.JsonNode) detail.get("snapshot");
        assertEquals("第一版提示词", snapshot.path("systemPrompt").asText());
        var second = publish("优化提示词");
        assertEquals(2, second.versions().size());
        assertEquals(2, second.versions().getFirst().number());
        var activated = versions.activate(agent.id(), v1);
        assertEquals(v1, activated.activeVersionId());
        assertTrue(activated.changed());
        assertEquals("第二版提示词", agents.findById(agent.id()).systemPrompt());
        assertFalse(snapshot.toString().contains("dummy-version-key"));
    }

    @Test
    void restoresAllDraftBindingsAndRulesAtomicallyWithoutMovingPublishedPointer() {
        var skill = config.saveSkill(null, new SkillInput("测试技能", "测试", "执行测试", true));
        config.bind(agent.id(), new SkillBinding(List.of(skill.id())));
        var server = tools.save(
            null,
            new ServerInput("版本MCP", "http://127.0.0.1:8090/mcp", "", true, 5, 1024)
        );
        tools.bindServers(agent.id(), new ServerBinding(List.of(server.id())));
        UUID kb = UUID.fromString(
            knowledge
                .create(owner, new KnowledgeDtos.BaseInput("版本知识库", "", "PRIVATE"))
                .get("id")
                .toString()
        );
        knowledge.bind(owner, agent.id(), List.of(kb));
        memory.setMode(owner, agent.id(), MemoryMode.CROSS_SESSION);
        var first = publish("完整绑定");
        UUID v1 = first.activeVersionId();
        config.profile(agent.id(), new Profile("新名字", "", "第二版提示词"));
        config.bind(agent.id(), new SkillBinding(List.of()));
        tools.bindServers(agent.id(), new ServerBinding(List.of()));
        knowledge.bind(owner, agent.id(), List.of());
        memory.setMode(owner, agent.id(), MemoryMode.NONE);
        config.saveRules(agent.id(), new Rules(3, 2, 60, 10, false, FailurePolicy.STOP));
        agents.bind(
            agent.id(),
            new Binding(agent.modelId(), json.readTree("{\"temperature\":0.2}"))
        );
        var second = publish("第二版");
        var restored = versions.restore(
            agent.id(),
            v1,
            new VersionDtos.Expected(second.fingerprint())
        );
        assertEquals(first.draft(), restored.draft());
        assertEquals(second.activeVersionId(), restored.activeVersionId());
        assertTrue(restored.changed());
        assertEquals(
            List.of(skill.id()),
            config.boundSkills(agent.id()).stream().map(Skill::id).toList()
        );
        assertEquals(1, knowledge.bound(owner, agent.id()).size());
        assertEquals(v1, versions.activate(agent.id(), v1).activeVersionId());
        assertFalse(versions.state(agent.id()).changed());
    }

    @Test
    void rejectsStaleDraftDuplicatePublicationAndForeignVersion() {
        var stale = versions.state(agent.id());
        config.profile(agent.id(), new Profile("更新后的名字", "", "当前提示词"));
        var conflict = assertThrows(ResponseStatusException.class, () ->
            versions.publish(agent.id(), new VersionDtos.Publish("过期发布", stale.fingerprint()))
        );
        assertEquals(409, conflict.getStatusCode().value());
        assertTrue(versions.state(agent.id()).versions().isEmpty());
        var current = publish("正常发布");
        assertThrows(ResponseStatusException.class, () ->
            versions.publish(agent.id(), new VersionDtos.Publish("重复发布", current.fingerprint()))
        );
        var other = agents.create(
            new CreateAgentRequest("另一个", "", "测试", json.createObjectNode())
        );
        var missing = assertThrows(ResponseStatusException.class, () ->
            versions.activate(other.id(), current.activeVersionId())
        );
        assertEquals(404, missing.getStatusCode().value());
        var noModel = assertThrows(ResponseStatusException.class, () ->
            versions.publish(
                other.id(),
                new VersionDtos.Publish("", versions.state(other.id()).fingerprint())
            )
        );
        assertEquals(400, noModel.getStatusCode().value());
    }

    @Test
    void refusesRestoreWhenPrivateReferencesAreNotAccessibleAndKeepsDraft() {
        UUID kb = UUID.fromString(
            knowledge
                .create(owner, new KnowledgeDtos.BaseInput("私有资料", "", "PRIVATE"))
                .get("id")
                .toString()
        );
        knowledge.bind(owner, agent.id(), List.of(kb));
        UUID v1 = publish("私有绑定").activeVersionId();
        knowledge.bind(owner, agent.id(), List.of());
        config.profile(agent.id(), new Profile("修改后的草稿", "", "新指令"));
        authenticate(user());
        var before = versions.state(agent.id());
        assertThrows(ResponseStatusException.class, () ->
            versions.restore(agent.id(), v1, new VersionDtos.Expected(before.fingerprint()))
        );
        assertEquals(before.fingerprint(), versions.state(agent.id()).fingerprint());
        assertEquals("新指令", agents.findById(agent.id()).systemPrompt());
    }

    @Test
    void versionHttpApiValidatesRequestsAndReturnsDraftState() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new VersionController(versions)
        ).build();
        String path = "/api/agents/" + agent.id() + "/versions";
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
        ).andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()
        );
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType("application/json")
                .content("{\"note\":\"\",\"expectedFingerprint\":\"bad\"}")
        ).andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest()
        );
        var result = mvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            new VersionDtos.Publish(
                                "API发布",
                                versions.state(agent.id()).fingerprint()
                            )
                        )
                    )
            )
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()
            )
            .andReturn()
            .getResponse();
        assertEquals(1, json.readTree(result.getContentAsString()).path("versions").size());
    }

    @AfterEach
    void clear() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
}
