package com.febridge.user;

import static com.febridge.user.UserDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "platform.auth.bootstrap-enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DB_URL", matches = ".+")
class UserSecurityTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    FilterChainProxy security;

    @Autowired
    UserService users;

    @Autowired
    UserMapper mapper;

    @Autowired
    JsonMapper json;

    MockMvc mvc;

    private record Client(MockHttpSession session, String csrf) {}

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
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    private UserView create(Role role) {
        return users.create(
            new CreateUser(
                "test_" + UUID.randomUUID().toString().substring(0, 20),
                "测试账号",
                "Test-only-password-123",
                role
            )
        );
    }

    private Client csrf(MockHttpSession session) throws Exception {
        var builder = get("/api/auth/csrf");
        if (session != null) {
            builder.session(session);
        }
        var result = mvc.perform(builder).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return new Client(
            (MockHttpSession) result.getRequest().getSession(),
            json.readTree(result.getResponse().getContentAsString()).path("token").asText()
        );
    }

    private Client login(UserView user) throws Exception {
        Client client = csrf(null);
        String oldId = client.session().getId();
        var result = mvc
            .perform(
                post("/api/auth/login")
                    .session(client.session())
                    .header("X-CSRF-TOKEN", client.csrf())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "username",
                                user.username().toUpperCase(),
                                "password",
                                "Test-only-password-123"
                            )
                        )
                    )
            )
            .andReturn();
        assertEquals(
            200,
            result.getResponse().getStatus(),
            result.getResponse().getContentAsString()
        );
        assertNotEquals(oldId, client.session().getId());
        assertFalse(result.getResponse().getContentAsString().contains("password"));
        return csrf(client.session());
    }

    private MvcResult postAs(Client client, String path, Object input) throws Exception {
        return mvc
            .perform(
                post(path)
                    .session(client.session())
                    .header("X-CSRF-TOKEN", client.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(input))
            )
            .andReturn();
    }

    @Test
    void protectsApiAndRejectsLoginWithoutCsrf() throws Exception {
        assertEquals(401, mvc.perform(get("/api/agents")).andReturn().getResponse().getStatus());
        assertEquals(
            200,
            mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()
        );
        assertEquals(
            403,
            mvc
                .perform(
                    post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"anything\"}")
                )
                .andReturn()
                .getResponse()
                .getStatus()
        );
    }

    @Test
    void enforcesUserAndAdministratorPrivileges() throws Exception {
        Client member = login(create(Role.USER));
        assertEquals(
            200,
            mvc
                .perform(get("/api/agents").session(member.session()))
                .andReturn()
                .getResponse()
                .getStatus()
        );
        assertEquals(
            200,
            mvc
                .perform(get("/api/models").session(member.session()))
                .andReturn()
                .getResponse()
                .getStatus()
        );
        assertEquals(
            403,
            mvc
                .perform(get("/api/users").session(member.session()))
                .andReturn()
                .getResponse()
                .getStatus()
        );
        assertEquals(403, postAs(member, "/api/models", Map.of()).getResponse().getStatus());
        assertEquals(403, postAs(member, "/api/mcp/servers", Map.of()).getResponse().getStatus());
        Client admin = login(create(Role.ADMIN));
        assertEquals(
            200,
            mvc
                .perform(get("/api/users").session(admin.session()))
                .andReturn()
                .getResponse()
                .getStatus()
        );
        var created = postAs(
            admin,
            "/api/users",
            new CreateUser(
                "new_" + UUID.randomUUID().toString().substring(0, 20),
                "新增用户",
                "New-password-12345",
                Role.USER
            )
        );
        assertEquals(200, created.getResponse().getStatus());
        assertFalse(created.getResponse().getContentAsString().contains("password"));
        assertFalse(created.getResponse().getContentAsString().contains("sessionVersion"));
    }

    @Test
    void roleChangeAndDisableInvalidateExistingSessions() throws Exception {
        var actor = create(Role.ADMIN);
        var other = create(Role.ADMIN);
        var session = login(other);
        users.update(actor.id(), other.id(), new UpdateUser("测试账号", Role.USER, true));
        assertEquals(
            401,
            mvc
                .perform(get("/api/auth/me").session(session.session()))
                .andReturn()
                .getResponse()
                .getStatus()
        );
        var next = login(users.find(other.id()).view());
        users.update(actor.id(), other.id(), new UpdateUser("测试账号", Role.USER, false));
        assertEquals(
            401,
            mvc
                .perform(get("/api/agents").session(next.session()))
                .andReturn()
                .getResponse()
                .getStatus()
        );
    }

    @Test
    void profileEditPreservesSessionButPasswordResetRevokesIt() throws Exception {
        var actor = create(Role.ADMIN);
        var member = create(Role.USER);
        var session = login(member);
        users.update(actor.id(), member.id(), new UpdateUser("新的显示名称", Role.USER, true));
        var response = mvc.perform(get("/api/auth/me").session(session.session())).andReturn();
        assertEquals(200, response.getResponse().getStatus());
        assertTrue(response.getResponse().getContentAsString().contains("新的显示名称"));
        users.resetPassword(actor.id(), member.id(), new PasswordReset("Reset-password-54321"));
        assertEquals(
            401,
            mvc
                .perform(get("/api/auth/me").session(session.session()))
                .andReturn()
                .getResponse()
                .getStatus()
        );
        assertThrows(ResponseStatusException.class, () ->
            users.authenticate(new Login(member.username(), "Test-only-password-123"))
        );
        assertNotNull(users.authenticate(new Login(member.username(), "Reset-password-54321")));
    }

    @Test
    void passwordChangeChecksOldPasswordAndRevokesOtherDevice() throws Exception {
        var member = create(Role.USER);
        var first = login(member);
        var second = login(member);
        assertEquals(
            400,
            postAs(
                first,
                "/api/auth/password",
                new PasswordChange("wrong-password", "Updated-password-54321")
            )
                .getResponse()
                .getStatus()
        );
        assertEquals(
            200,
            postAs(
                first,
                "/api/auth/password",
                new PasswordChange("Test-only-password-123", "Updated-password-54321")
            )
                .getResponse()
                .getStatus()
        );
        assertEquals(
            401,
            mvc
                .perform(get("/api/auth/me").session(second.session()))
                .andReturn()
                .getResponse()
                .getStatus()
        );
        assertNotNull(users.authenticate(new Login(member.username(), "Updated-password-54321")));
    }

    @Test
    void logsOutAndRejectsMissingCsrfOnAuthenticatedMutation() throws Exception {
        var client = login(create(Role.ADMIN));
        assertEquals(
            403,
            mvc
                .perform(
                    post("/api/users")
                        .session(client.session())
                        .contentType("application/json")
                        .content("{}")
                )
                .andReturn()
                .getResponse()
                .getStatus()
        );
        assertEquals(200, postAs(client, "/api/auth/logout", Map.of()).getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/auth/me")).andReturn().getResponse().getStatus());
    }

    @Test
    void rejectsDuplicateNamesAndSelfDemotionAndHashesPasswords() {
        var user = create(Role.ADMIN);
        var record = mapper.byId(user.id());
        assertNotEquals("Test-only-password-123", record.passwordHash());
        assertThrows(ResponseStatusException.class, () ->
            users.create(
                new CreateUser(
                    user.username().toUpperCase(),
                    "重复账号",
                    "Other-password-12345",
                    Role.USER
                )
            )
        );
        assertThrows(ResponseStatusException.class, () ->
            users.update(user.id(), user.id(), new UpdateUser("管理员", Role.USER, true))
        );
        assertThrows(ResponseStatusException.class, () ->
            users.update(user.id(), user.id(), new UpdateUser("管理员", Role.ADMIN, false))
        );
    }

    @Test
    void locksRepeatedFailedLoginsAndKeepsFailureResponseGeneric() {
        var user = create(Role.USER);
        for (int i = 0; i < 10; i++) {
            assertThrows(ResponseStatusException.class, () ->
                users.authenticate(new Login(user.username(), "Wrong-password-123"))
            );
        }
        assertTrue(mapper.byId(user.id()).locked());
        assertThrows(ResponseStatusException.class, () ->
            users.authenticate(new Login(user.username(), "Test-only-password-123"))
        );
    }
}
