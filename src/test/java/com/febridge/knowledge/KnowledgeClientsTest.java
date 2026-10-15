package com.febridge.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeClientsTest {

    private HttpServer server;
    private KnowledgeClients client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, String> bodies = new HashMap<>();
    private String embedding =
        "{\"model\":\"bge-m3\",\"data\":[{\"index\":0,\"embedding\":[1.0,0.0,0.0]}]}";
    private final UUID point = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            bodies.put(
                exchange.getRequestMethod() + " " + path,
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
            );
            String response = "{\"status\":\"ok\",\"result\":true}";
            if (path.equals("/v1/embeddings")) {
                assertEquals(
                    "Bearer test-embed",
                    exchange.getRequestHeaders().getFirst("Authorization")
                );
                response = embedding;
            } else {
                assertEquals("test-qdrant", exchange.getRequestHeaders().getFirst("api-key"));
                if (
                    path.endsWith("/test_collection") && exchange.getRequestMethod().equals("GET")
                ) {
                    response =
                        "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}";
                } else if (path.endsWith("/query")) {
                    response =
                        "{\"result\":{\"points\":[{\"id\":\"" + point + "\",\"score\":0.9}]}}";
                }
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new KnowledgeClients(
            json,
            url,
            "test-embed",
            "bge-m3",
            url,
            "test-qdrant",
            "test_collection",
            "test-v1",
            3
        );
    }

    @Test
    void usesCorrectContractsAndPermissionFiltersWithoutSendingRawDocumentToQdrant() {
        UUID kb = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        var vectors = client.embed(List.of("合成测试文本"), Duration.ofSeconds(5));
        client.upsert(kb, document, List.of(point), vectors);
        var points = client.query(
            List.of(kb.toString()),
            vectors.get(0),
            5,
            Duration.ofSeconds(5)
        );
        assertEquals(point.toString(), points.get(0).path("id").asText());
        var query = json.readTree(bodies.get("POST /collections/test_collection/points/query"));
        assertEquals(
            kb.toString(),
            query
                .path("filter")
                .path("must")
                .get(0)
                .path("match")
                .path("any")
                .get(0)
                .asText()
        );
        String upsert = bodies.get("PUT /collections/test_collection/points");
        assertFalse(upsert.contains("合成测试文本"));
        assertTrue(upsert.contains("embedding_space"));
        client.delete(document);
        assertTrue(
            bodies
                .get("POST /collections/test_collection/points/delete")
                .contains(document.toString())
        );
    }

    @Test
    void rejectsWrongDimensionAndModelInsteadOfWritingIncompatibleVectors() {
        embedding = "{\"model\":\"bge-m3\",\"data\":[{\"index\":0,\"embedding\":[1.0]}]}";
        assertThrows(ResponseStatusException.class, () ->
            client.embed(List.of("测试"), Duration.ofSeconds(5))
        );
        embedding =
            "{\"model\":\"other-model\",\"data\":[{\"index\":0,\"embedding\":[1.0,0.0,0.0]}]}";
        assertThrows(ResponseStatusException.class, () ->
            client.embed(List.of("测试"), Duration.ofSeconds(5))
        );
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }
}
