package com.febridge.conversation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationContextTest {

    @Test
    void keepsRecentCompletePairsInsideCharacterBudget() {
        var old = Map.<String, Object>of(
            "user_content",
            "长".repeat(24000),
            "assistant_content",
            "旧回答"
        );
        var recent = Map.<String, Object>of(
            "user_content",
            "新问题",
            "assistant_content",
            "新回答"
        );
        var context = ConversationStore.buildContext(List.of(old, recent));
        assertEquals(2, context.size());
        assertEquals("user", context.getFirst().role());
        assertEquals("新问题", context.getFirst().content());
        assertEquals("assistant", context.getLast().role());
    }
}
