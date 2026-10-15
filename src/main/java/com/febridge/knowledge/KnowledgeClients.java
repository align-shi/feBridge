package com.febridge.knowledge;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class KnowledgeClients {

    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private final String embeddingUrl;
    private final String embeddingKey;
    private final String model;
    private final String qdrantUrl;
    private final String qdrantKey;
    private final String collection;
    private final String space;
    private final int dimensions;
    private volatile boolean initialized;

    public KnowledgeClients(
        JsonMapper json,
        @Value("${platform.knowledge.embedding-url:http://127.0.0.1:8091}") String embeddingUrl,
        @Value("${platform.knowledge.embedding-key:}") String embeddingKey,
        @Value("${platform.knowledge.embedding-model:bge-m3}") String model,
        @Value("${platform.knowledge.qdrant-url:http://127.0.0.1:6333}") String qdrantUrl,
        @Value("${platform.knowledge.qdrant-key:}") String qdrantKey,
        @Value("${platform.knowledge.collection:febridge_kb_bge_m3_v1}") String collection,
        @Value("${platform.knowledge.embedding-space:bge-m3-v1}") String space,
        @Value("${platform.knowledge.dimensions:1024}") int dimensions
    ) {
        this.json = json;
        this.embeddingUrl = embeddingUrl.replaceAll("/+$", "");
        this.embeddingKey = embeddingKey;
        this.model = model;
        this.qdrantUrl = qdrantUrl.replaceAll("/+$", "");
        this.qdrantKey = qdrantKey;
        this.collection = collection;
        this.space = space;
        this.dimensions = dimensions;
        if (
            !collection.matches("[a-zA-Z0-9_-]{1,100}") ||
            dimensions < 1 ||
            !space.matches("[a-zA-Z0-9_.-]{1,100}")
        ) {
            throw new IllegalArgumentException("知识库向量配置不正确");
        }
    }

    public String space() {
        return space;
    }

    public List<List<Double>> embed(List<String> texts, Duration timeout) {
        var body = json.createObjectNode().put("model", model).put("encoding_format", "float");
        body.set("input", json.valueToTree(texts));
        var result = send(
            "POST",
            embeddingUrl + "/v1/embeddings",
            body,
            "Authorization",
            embeddingKey.isBlank() ? "" : "Bearer " + embeddingKey,
            timeout,
            false
        );
        var data = result.path("data");
        if (
            !model.equals(result.path("model").asText()) ||
            !data.isArray() ||
            data.size() != texts.size()
        ) {
            throw unavailable("Embedding 返回数量或模型不正确");
        }
        var vectors = new ArrayList<List<Double>>();
        for (int i = 0; i < data.size(); i++) {
            var item = data.get(i);
            var vector = item.path("embedding");
            if (
                item.path("index").asInt(-1) != i ||
                !vector.isArray() ||
                vector.size() != dimensions
            ) {
                throw unavailable("Embedding 向量维度或顺序不正确");
            }
            var values = new ArrayList<Double>();
            double norm = 0;
            for (var number : vector) {
                double value = number.asDouble(Double.NaN);
                if (!number.isNumber() || !Double.isFinite(value)) {
                    throw unavailable("Embedding 返回非有效数值");
                }
                values.add(value);
                norm += value * value;
            }
            if (!Double.isFinite(norm) || norm <= 0) {
                throw unavailable("Embedding 返回无效向量");
            }
            vectors.add(values);
        }
        return vectors;
    }

    public synchronized void ensureCollection() {
        if (initialized) {
            return;
        }
        String url = qdrantUrl + "/collections/" + collection;
        var response = send("GET", url, null, "api-key", qdrantKey, Duration.ofSeconds(10), true);
        if (response == null) {
            var body = json.createObjectNode();
            body.set(
                "vectors",
                json.createObjectNode().put("size", dimensions).put("distance", "Cosine")
            );
            try {
                send("PUT", url, body, "api-key", qdrantKey, Duration.ofSeconds(20), false);
            } catch (ResponseStatusException race) {
                // A second worker may have created the same collection.
            }
            response = send("GET", url, null, "api-key", qdrantKey, Duration.ofSeconds(10), false);
        }
        var vectors = response.path("result").path("config").path("params").path("vectors");
        if (
            vectors.path("size").asInt() != dimensions ||
            !"Cosine".equals(vectors.path("distance").asText())
        ) {
            throw unavailable("Qdrant 集合维度或距离类型不匹配，请配置新的独立集合");
        }
        for (String field : List.of("kb_id", "document_id", "embedding_space")) {
            send(
                "PUT",
                url + "/index?wait=true",
                json.createObjectNode().put("field_name", field).put("field_schema", "keyword"),
                "api-key",
                qdrantKey,
                Duration.ofSeconds(20),
                false
            );
        }
        initialized = true;
    }

    public void upsert(UUID kb, UUID document, List<UUID> ids, List<List<Double>> vectors) {
        ensureCollection();
        var body = json.createObjectNode();
        var points = body.putArray("points");
        for (int i = 0; i < ids.size(); i++) {
            var point = points.addObject().put("id", ids.get(i).toString());
            point.set("vector", json.valueToTree(vectors.get(i)));
            point.set(
                "payload",
                json
                    .createObjectNode()
                    .put("kb_id", kb.toString())
                    .put("document_id", document.toString())
                    .put("embedding_space", space)
            );
        }
        send(
            "PUT",
            qdrantUrl + "/collections/" + collection + "/points?wait=true",
            body,
            "api-key",
            qdrantKey,
            Duration.ofSeconds(30),
            false
        );
    }

    public JsonNode query(List<String> kbIds, List<Double> vector, int limit, Duration timeout) {
        var body = json
            .createObjectNode()
            .put("limit", limit)
            .put("with_payload", false)
            .put("with_vector", false);
        body.set("query", json.valueToTree(vector));
        var filter = body.putObject("filter").putArray("must");
        var access = filter.addObject().put("key", "kb_id");
        access.putObject("match").set("any", json.valueToTree(kbIds));
        filter.addObject().put("key", "embedding_space").putObject("match").put("value", space);
        return send(
            "POST",
            qdrantUrl + "/collections/" + collection + "/points/query",
            body,
            "api-key",
            qdrantKey,
            timeout,
            false
        )
            .path("result")
            .path("points");
    }

    public void delete(UUID document) {
        ensureCollection();
        var body = json.createObjectNode();
        body.putObject("filter")
            .putArray("must")
            .addObject()
            .put("key", "document_id")
            .putObject("match")
            .put("value", document.toString());
        send(
            "POST",
            qdrantUrl + "/collections/" + collection + "/points/delete?wait=true",
            body,
            "api-key",
            qdrantKey,
            Duration.ofSeconds(30),
            false
        );
    }

    private JsonNode send(
        String method,
        String url,
        JsonNode body,
        String header,
        String key,
        Duration timeout,
        boolean allow404
    ) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json");
            if (!key.isBlank()) {
                builder.header(header, key);
            }
            builder.method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body.toString())
            );
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() == 404 && allow404) {
                    return null;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw unavailable(
                        "知识库外部服务请求失败（HTTP " + response.statusCode() + "）"
                    );
                }
                byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
                if (bytes.length > 8 * 1024 * 1024) {
                    throw unavailable("知识库服务响应过大");
                }
                return json.readTree(bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("知识库请求已中断");
        } catch (IOException | IllegalArgumentException exception) {
            throw unavailable("无法连接或解析 Embedding/Qdrant 服务，请检查后端配置");
        }
    }

    static ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}
