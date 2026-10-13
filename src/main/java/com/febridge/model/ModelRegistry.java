package com.febridge.model;

import static com.febridge.model.ModelDtos.*;

import com.febridge.security.SecretCipher;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ModelRegistry {

    private final ModelMapper mapper;
    private final SecretCipher cipher;
    private final JsonMapper json;

    public ModelRegistry(ModelMapper mapper, SecretCipher cipher, JsonMapper json) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.json = json;
    }

    public List<ProviderView> providers() {
        return mapper.providers().stream().map(this::providerView).toList();
    }

    public List<ModelView> models() {
        return mapper.models().stream().map(this::modelView).toList();
    }

    public List<Preset> presets() {
        return Arrays.stream(ProviderType.values())
            .map(p -> new Preset(p, p.baseUrl(), p.suggestedModel()))
            .toList();
    }

    @Transactional
    public ProviderView createProvider(ProviderInput input) {
        String key = cleanKey(input.apiKey());
        UUID id = UUID.randomUUID();
        mapper.insertProvider(
            id,
            input.name().strip(),
            input.providerType().name(),
            input.providerType().baseUrl(),
            cipher.encrypt(key),
            input.enabled()
        );
        return providerView(providerRow(id));
    }

    @Transactional
    public ProviderView updateProvider(UUID id, ProviderInput input) {
        var row = providerRow(id);
        if (!input.providerType().name().equals(row.get("provider_type"))) {
            bad("厂商类型不能修改，请新建提供商");
        }
        String encrypted =
            input.apiKey() == null || input.apiKey().isBlank()
                ? row.get("api_key_ciphertext").toString()
                : cipher.encrypt(cleanKey(input.apiKey()));
        mapper.updateProvider(id, input.name().strip(), encrypted, input.enabled());
        return providerView(providerRow(id));
    }

    @Transactional
    public ModelView createModel(ModelInput input) {
        providerRow(input.providerId());
        var parameters = parameters(input.defaultParameters());
        UUID id = UUID.randomUUID();
        mapper.insertModel(
            id,
            input.providerId(),
            input.modelCode().strip(),
            input.displayName().strip(),
            input.supportsTools(),
            parameters.toString(),
            input.enabled()
        );
        return getModel(id);
    }

    @Transactional
    public ModelView updateModel(UUID id, ModelInput input) {
        getModel(id);
        providerRow(input.providerId());
        mapper.updateModel(
            id,
            input.providerId(),
            input.modelCode().strip(),
            input.displayName().strip(),
            input.supportsTools(),
            parameters(input.defaultParameters()).toString(),
            input.enabled()
        );
        return getModel(id);
    }

    public ModelView getModel(UUID id) {
        var row = mapper.model(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在");
        }
        return modelView(row);
    }

    public ModelView availableModel(UUID id) {
        var model = getModel(id);
        if (!model.enabled()) {
            bad("模型已停用");
        }
        var provider = providerView(providerRow(model.providerId()));
        if (!provider.enabled()) {
            bad("提供商已停用");
        }
        return model;
    }

    public Connection connection(UUID modelId) {
        var model = availableModel(modelId);
        var row = providerRow(model.providerId());
        var type = ProviderType.valueOf(row.get("provider_type").toString());
        // Only official endpoints are accepted, even if a database row was edited.
        if (!type.baseUrl().equals(row.get("base_url"))) {
            bad("提供商地址与官方地址不匹配");
        }
        return new Connection(
            model,
            type.baseUrl(),
            cipher.decrypt(row.get("api_key_ciphertext").toString()),
            row.get("name").toString()
        );
    }

    public record Connection(ModelView model, String baseUrl, String apiKey, String providerName) {
        @Override
        public String toString() {
            return "ModelConnection[model=" + model.modelCode() + "]";
        }
    }

    public JsonNode parameters(JsonNode input) {
        if (input == null) {
            return json.createObjectNode();
        }
        if (!input.isObject()) {
            bad("模型参数必须是 JSON 对象");
        }
        Set<String> allowed = Set.of(
            "temperature",
            "top_p",
            "max_tokens",
            "presence_penalty",
            "frequency_penalty",
            "thinking",
            "reasoning_effort"
        );
        var properties = input.properties();
        for (var field : properties) {
            if (!allowed.contains(field.getKey())) {
                bad("不支持的模型参数：" + field.getKey());
            }
        }
        for (String name : List.of(
            "temperature",
            "top_p",
            "presence_penalty",
            "frequency_penalty"
        )) {
            var value = input.get(name);
            if (value != null && !value.isNumber()) {
                bad(name + " 必须是数字");
            }
        }
        if (
            input.has("temperature") &&
            (input.get("temperature").asDouble() < 0 || input.get("temperature").asDouble() > 2)
        ) {
            bad("temperature 范围为 0 到 2");
        }
        if (
            input.has("top_p") &&
            (input.get("top_p").asDouble() <= 0 || input.get("top_p").asDouble() > 1)
        ) {
            bad("top_p 范围为大于 0 且不超过 1");
        }
        if (
            input.has("max_tokens") &&
            (!input.get("max_tokens").isIntegralNumber() ||
                input.get("max_tokens").asLong() < 1 ||
                input.get("max_tokens").asLong() > 65536)
        ) {
            bad("max_tokens 必须在 1 到 65536 之间");
        }
        if (
            input.has("thinking") &&
            (!input.get("thinking").isObject() ||
                !Set.of("enabled", "disabled").contains(
                    input.get("thinking").path("type").asText()
                ))
        ) {
            bad("thinking.type 必须为 enabled 或 disabled");
        }
        if (
            input.has("reasoning_effort") &&
            !Set.of("low", "medium", "high", "max").contains(input.get("reasoning_effort").asText())
        ) {
            bad("reasoning_effort 参数无效");
        }
        return input;
    }

    private String cleanKey(String key) {
        if (key == null || key.isBlank() || key.contains("\n") || key.contains("\r")) {
            bad("请填写有效的 API Key");
        }
        return key.strip();
    }

    private Map<String, Object> providerRow(UUID id) {
        var row = mapper.provider(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提供商不存在");
        }
        return row;
    }

    private ProviderView providerView(Map<String, Object> row) {
        return new ProviderView(
            uuid(row, "id"),
            row.get("name").toString(),
            ProviderType.valueOf(row.get("provider_type").toString()),
            row.get("base_url").toString(),
            row.get("api_key_ciphertext") != null,
            (boolean) row.get("enabled"),
            time(row)
        );
    }

    private ModelView modelView(Map<String, Object> row) {
        return new ModelView(
            uuid(row, "id"),
            uuid(row, "provider_id"),
            row.get("model_code").toString(),
            row.get("display_name").toString(),
            (boolean) row.get("supports_tools"),
            json.readTree(row.get("default_parameters").toString()),
            (boolean) row.get("enabled"),
            time(row)
        );
    }

    private UUID uuid(Map<String, Object> row, String key) {
        return UUID.fromString(row.get(key).toString());
    }

    private OffsetDateTime time(Map<String, Object> row) {
        Object value = row.get("created_at");
        return value instanceof OffsetDateTime time
            ? time
            : ((Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
