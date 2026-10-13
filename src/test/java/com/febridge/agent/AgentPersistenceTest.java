package com.febridge.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "platform.auth.bootstrap-enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DB_URL", matches = ".+")
class AgentPersistenceTest {

    @Autowired
    AgentService service;

    @Autowired
    JsonMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("PLATFORM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () ->
            System.getenv("PLATFORM_TEST_DB_USERNAME")
        );
        registry.add("spring.datasource.password", () ->
            System.getenv("PLATFORM_TEST_DB_PASSWORD")
        );
    }

    @Test
    void migratesAndRoundTripsUuidJsonbAndTimestamp() {
        var config = json.createObjectNode().put("model", "demo").put("temperature", 0.2);
        var saved = service.create(
            new CreateAgentRequest("销售助手", "测试", "根据客户信息回答", config)
        );
        var loaded = service.findById(saved.id());
        assertEquals("销售助手", loaded.name());
        assertEquals(config, loaded.modelConfig());
        assertNotNull(loaded.createdAt());
        assertTrue(
            service
                .findRecent()
                .stream()
                .anyMatch(agent -> agent.id().equals(saved.id()))
        );
    }
}
