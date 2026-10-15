package com.febridge.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.febridge.user.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
        "platform.auth.bootstrap-enabled=false",
        "platform.knowledge.worker-enabled=false",
        "platform.memory.worker-enabled=false",
    }
)
@Transactional
@EnabledIfEnvironmentVariable(named = "PLATFORM_LIVE_KB_TEST", matches = "true")
class KnowledgeLiveTest {

    static final String COLLECTION =
        "febridge_kb_acceptance_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    KnowledgeStore store;

    @Autowired
    KnowledgeSearch search;

    @Autowired
    KnowledgeClients clients;

    @Autowired
    UserService users;

    @Autowired
    Environment environment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("PLATFORM_TEST_DB_URL"));
        properties.add("spring.datasource.username", () ->
            System.getenv("PLATFORM_TEST_DB_USERNAME")
        );
        properties.add("spring.datasource.password", () ->
            System.getenv("PLATFORM_TEST_DB_PASSWORD")
        );
        properties.add("platform.knowledge.collection", () -> COLLECTION);
    }

    @Test
    void embedsIndexesAndRetrievesUsingRealServices() {
        UUID owner = users
            .create(
                new UserDtos.CreateUser(
                    "live_" + UUID.randomUUID().toString().substring(0, 20),
                    "知识库验收用户",
                    "Acceptance-Password-12345",
                    UserDtos.Role.USER
                )
            )
            .id();
        UUID kb = UUID.fromString(
            store
                .create(
                    owner,
                    new KnowledgeDtos.BaseInput(
                        "验收专用知识库",
                        "合成数据，不含业务资料",
                        "PRIVATE"
                    )
                )
                .get("id")
                .toString()
        );
        UUID document = store.upload(
            owner,
            kb,
            new KnowledgeDtos.DocumentInput(
                "验收调拨规范.md",
                "# 模具调拨规范\n模具跨工厂调拨必须经过调出工厂和调入工厂审批。审批通过后才允许安排运输。"
            ),
            clients.space()
        );
        var worker = new KnowledgeWorker(store, clients);
        worker.process(store.claim());
        var row = store.documents(owner, kb).getFirst();
        assertEquals("ready", row.get("status"), "实际入库失败：" + row.get("error_message"));
        var hits = search.search(
            owner,
            List.of(kb.toString()),
            "模具跨工厂调拨需要谁审批？",
            3,
            Duration.ofSeconds(25)
        );
        assertFalse(hits.isEmpty());
        assertEquals(document, hits.getFirst().documentId());
        assertTrue(hits.getFirst().content().contains("调入工厂"));
        store.action(owner, kb, document, true);
        worker.process(store.claim());
        assertTrue(store.documents(owner, kb).isEmpty());
        System.out.println(
            "Live knowledge acceptance passed: real embedding, Qdrant upsert/query/delete."
        );
    }

    @AfterEach
    void removeOwnAcceptanceCollection() throws Exception {
        String base = environment.getProperty("platform.knowledge.qdrant-url");
        String key = environment.getProperty("platform.knowledge.qdrant-key");
        var request = HttpRequest.newBuilder(URI.create(base + "/collections/" + COLLECTION))
            .header("api-key", key)
            .timeout(Duration.ofSeconds(15))
            .DELETE()
            .build();
        int status = HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.discarding())
            .statusCode();
        assertTrue(status == 200 || status == 404, "验收集合清理失败：" + status);
    }
}
