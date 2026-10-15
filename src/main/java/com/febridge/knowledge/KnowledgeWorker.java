package com.febridge.knowledge;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "platform.knowledge.worker-enabled", matchIfMissing = true)
public class KnowledgeWorker {

    private final KnowledgeStore store;
    private final KnowledgeClients clients;

    public KnowledgeWorker(KnowledgeStore store, KnowledgeClients clients) {
        this.store = store;
        this.clients = clients;
    }

    @Configuration
    @EnableScheduling
    static class Scheduling {}

    @Scheduled(
        fixedDelayString = "${platform.knowledge.poll-millis:3000}",
        initialDelayString = "${platform.knowledge.initial-delay-millis:10000}"
    )
    public void poll() {
        var job = store.claim();
        if (job != null) {
            process(job);
        }
    }

    public void process(Map<String, Object> job) {
        boolean deleting = "deleting".equals(job.get("status"));
        try {
            if (((Number) job.get("attempts")).intValue() > 3) {
                throw new IllegalStateException("已达到处理重试上限");
            }
            UUID document = UUID.fromString(job.get("id").toString());
            if (deleting) {
                clients.delete(document);
                store.finish(job, "deleted", null, 0);
                return;
            }
            if (!clients.space().equals(job.get("embedding_space"))) {
                throw new IllegalStateException("文档向量空间与当前配置不一致");
            }
            var texts = TextChunks.split(job.get("content").toString());
            var ids = store.saveChunks(job, texts);
            for (int offset = 0; offset < texts.size(); offset += 8) {
                int end = Math.min(offset + 8, texts.size());
                var vectors = clients.embed(texts.subList(offset, end), Duration.ofSeconds(60));
                clients.upsert(
                    UUID.fromString(job.get("kb_id").toString()),
                    document,
                    ids.subList(offset, end),
                    vectors
                );
            }
            store.finish(job, "ready", null, texts.size());
        } catch (RuntimeException exception) {
            String reason = "处理失败，请检查Embedding与Qdrant配置后重试";
            if (exception instanceof ResponseStatusException status && status.getReason() != null) {
                reason = status.getReason();
            }
            store.finish(job, deleting ? "delete_failed" : "failed", reason, 0);
        }
    }
}
