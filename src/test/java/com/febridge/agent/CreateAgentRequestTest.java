package com.febridge.agent;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CreateAgentRequestTest {

    @Test
    void rejectsBlankPromptAndNonObjectConfiguration() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new CreateAgentRequest(
                "demo",
                "",
                " ",
                JsonMapper.builder().build().createArrayNode()
            );
            var violations = factory.getValidator().validate(request);
            assertTrue(
                violations
                    .stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("systemPrompt"))
            );
            assertTrue(
                violations
                    .stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("modelConfigValid"))
            );
        }
    }
}
