package com.febridge.memory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LongMemoryExtractorTest {

    private final LongMemoryExtractor extractor = new LongMemoryExtractor();

    @Test
    void extractsOnlyExplicitDeclarationsAndLatestCorrection() {
        var result = extractor.extract(
            "以后默认查询苏州工厂。以后默认查询杭州工厂。以后请用中文回答"
        );
        assertEquals(2, result.size());
        assertTrue(
            result
                .stream()
                .anyMatch(
                    item -> item.key().equals("default.factory") && item.content().contains("杭州")
                )
        );
        assertEquals(1, extractor.extract("我长期负责注塑模具").size());
    }

    @Test
    void rejectsQuestionsNegationQuotesAndSensitiveValues() {
        for (String input : java.util.List.of(
            "以后默认查询苏州工厂吗？",
            "不要记住以后请用中文回答",
            "他说：以后请用中文回答",
            "假如我长期负责注塑模具",
            "我的岗位是密码管理员",
            "查询M001模具",
            "我的API Key是abc123"
        )) {
            assertTrue(extractor.extract(input).isEmpty(), input);
        }
    }

    @Test
    void matchesBusinessKeywordsWithoutTreatingEveryFactAsRelevant() {
        assertTrue(LongMemoryStore.relevant("查询注塑模具", "我长期负责注塑模具"));
        assertFalse(LongMemoryStore.relevant("天气如何", "我长期负责注塑模具"));
        assertTrue(LongMemoryStore.relevant("check order", "order management"));
    }
}
