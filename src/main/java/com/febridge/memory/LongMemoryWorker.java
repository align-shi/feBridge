package com.febridge.memory;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.memory.worker-enabled", matchIfMissing = true)
public class LongMemoryWorker {

    private final LongMemoryStore store;
    private final LongMemoryExtractor extractor;

    public LongMemoryWorker(LongMemoryStore store, LongMemoryExtractor extractor) {
        this.store = store;
        this.extractor = extractor;
    }

    @Configuration
    @EnableScheduling
    static class Scheduling {}

    @Scheduled(
        fixedDelayString = "${platform.memory.poll-millis:3000}",
        initialDelayString = "${platform.memory.initial-delay-millis:10000}"
    )
    public void poll() {
        var job = store.claim();
        if (job != null) {
            process(job);
        }
    }

    void process(Map<String, Object> job) {
        try {
            var source = store.source(job);
            if (
                source == null ||
                !Boolean.TRUE.equals(source.get("user_enabled")) ||
                ((Number) job.get("attempts")).intValue() > 3
            ) {
                store.finish(job, List.of(), "任务已失效");
                return;
            }
            store.finish(job, extractor.extract(source.get("user_content").toString()), null);
        } catch (RuntimeException exception) {
            store.finish(job, List.of(), "自动提取失败，可继续正常对话");
        }
    }
}
