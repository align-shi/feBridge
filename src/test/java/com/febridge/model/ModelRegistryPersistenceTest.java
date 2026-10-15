package com.febridge.model;

import static com.febridge.model.ModelDtos.*;
import static org.junit.jupiter.api.Assertions.*;

import com.febridge.agent.*;
import com.febridge.security.SecretCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "platform.auth.bootstrap-enabled=false")
@Transactional
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DB_URL", matches = ".+")
class ModelRegistryPersistenceTest {

    @Autowired
    ModelRegistry registry;

    @Autowired
    ModelMapper mapper;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AgentService agents;

    @Autowired
    JsonMapper json;

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

    @Test
    void encryptsKeysBindsModelsAndHonorsDisabledProviders() {
        var provider = registry.createProvider(
            new ProviderInput("测试提供商", ProviderType.DEEPSEEK, "dummy-integration-key", true)
        );
        assertTrue(provider.keyConfigured());
        assertFalse(json.writeValueAsString(provider).contains("dummy-integration-key"));
        var encrypted = mapper.provider(provider.id()).get("api_key_ciphertext").toString();
        assertFalse(encrypted.contains("dummy-integration-key"));
        assertEquals("dummy-integration-key", cipher.decrypt(encrypted));
        var model = registry.createModel(
            new ModelInput(
                provider.id(),
                "test-model",
                "测试模型",
                true,
                json.createObjectNode().put("temperature", 0.2),
                true
            )
        );
        var agent = agents.create(
            new CreateAgentRequest(
                "测试 Agent",
                "",
                "系统提示",
                json.createObjectNode(),
                model.id()
            )
        );
        assertEquals(model.id(), agents.findById(agent.id()).modelId());
        var updated = agents.bind(
            agent.id(),
            new Binding(model.id(), json.createObjectNode().put("max_tokens", 32))
        );
        assertEquals(32, updated.modelConfig().path("max_tokens").asInt());
        registry.updateProvider(
            provider.id(),
            new ProviderInput("更名", ProviderType.DEEPSEEK, "", true)
        );
        assertEquals(
            encrypted,
            mapper.provider(provider.id()).get("api_key_ciphertext").toString()
        );
        registry.updateProvider(
            provider.id(),
            new ProviderInput("停用", ProviderType.DEEPSEEK, "", false)
        );
        assertThrows(ResponseStatusException.class, () -> registry.connection(model.id()));
    }

    @Test
    void rejectsProtocolAndCredentialOverrides() {
        assertThrows(ResponseStatusException.class, () ->
            agents.create(
                new CreateAgentRequest(
                    "测试",
                    "",
                    "系统提示",
                    json.createObjectNode().put("apiKey", "do-not-store")
                )
            )
        );
        assertThrows(ResponseStatusException.class, () ->
            registry.parameters(json.createObjectNode().put("apiKey", "do-not-store"))
        );
        assertThrows(ResponseStatusException.class, () ->
            registry.parameters(json.createObjectNode().put("messages", "override"))
        );
        assertThrows(ResponseStatusException.class, () ->
            registry.parameters(json.createObjectNode().put("temperature", 5))
        );
    }

    @Test
    void persistsAllProviderPresetsAndEditableEndpoints() {
        assertEquals(ProviderType.values().length, registry.presets().size());
        for (var type : ProviderType.values()) {
            String url = type == ProviderType.CUSTOM ? "http://127.0.0.1:9000/v1" : type.baseUrl();
            var provider = registry.createProvider(
                new ProviderInput(type.label(), type, "dummy-key", true, url + "/")
            );
            assertEquals(url, provider.baseUrl());
            assertEquals(type, provider.providerType());
        }
        var provider = registry.createProvider(
            new ProviderInput(
                "自定义",
                ProviderType.CUSTOM,
                "dummy-key",
                true,
                "https://gateway.example/v1/chat/completions"
            )
        );
        assertEquals("https://gateway.example/v1", provider.baseUrl());
        var updated = registry.updateProvider(
            provider.id(),
            new ProviderInput(
                "更名",
                ProviderType.CUSTOM,
                "",
                true,
                "https://other.example/openai/v1/"
            )
        );
        assertEquals("https://other.example/openai/v1", updated.baseUrl());
        assertEquals(
            "dummy-key",
            cipher.decrypt(mapper.provider(provider.id()).get("api_key_ciphertext").toString())
        );
        updated = registry.updateProvider(
            provider.id(),
            new ProviderInput("旧接口更新", ProviderType.CUSTOM, "", true)
        );
        assertEquals("https://other.example/openai/v1", updated.baseUrl());
        var customModel = registry.createModel(
            new ModelInput(
                provider.id(),
                "custom-test",
                "自定义模型",
                true,
                json.createObjectNode(),
                true
            )
        );
        assertEquals(
            "https://other.example/openai/v1",
            registry.connection(customModel.id()).baseUrl()
        );
    }

    @Test
    void rejectsInvalidBaseUrlsWithoutNetworkCalls() {
        for (String url : java.util.List.of(
            "",
            "relative/path",
            "file:///secret",
            "https://user:secret@example.com/v1",
            "https://example.com/v1?api_key=secret",
            "https://example.com/v1#fragment",
            "https://example.com:70000/v1"
        )) {
            assertThrows(ResponseStatusException.class, () -> ModelRegistry.normalizeBaseUrl(url));
        }
        assertThrows(ResponseStatusException.class, () ->
            registry.createProvider(new ProviderInput("自定义", ProviderType.CUSTOM, "dummy", true))
        );
    }
}
