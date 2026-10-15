package com.febridge.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextChunksTest {

    @Test
    void preservesUnicodeAndOverlapWithBoundedLengths() {
        String input = "😀模具审批。\n".repeat(200);
        var parts = TextChunks.split(input);
        assertTrue(parts.size() > 1);
        assertTrue(parts.stream().allMatch(part -> part.codePointCount(0, part.length()) <= 300));
        for (var part : parts) {
            assertFalse(part.contains("\uFFFD"));
        }
        assertEquals(java.util.List.of(), TextChunks.split(" \r\n "));
        assertEquals(java.util.List.of("模具\n审批"), TextChunks.split("模具\r\n审批"));
    }
}
