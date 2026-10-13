package com.febridge.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiChatClientTest {

    @Test
    void sendsAgentPromptAndParsesAnswerAndUsage() throws Exception {
        var json = JsonMapper.builder().build();
        var request = new AtomicReference<JsonNode>();
        var auth = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            request.set(
                json.readTree(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                )
            );
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response =
                "{\"choices\":[{\"message\":{\"content\":\"你好\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}".getBytes(
                    StandardCharsets.UTF_8
                );
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var result = new OpenAiChatClient(json).complete(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "dummy-test-key",
                "test-model",
                "只用中文回答",
                "你好",
                json.createObjectNode().put("temperature", 0.2)
            );
            assertEquals("你好", result.content());
            assertEquals(12L, result.usage().totalTokens());
            assertEquals("Bearer dummy-test-key", auth.get());
            assertEquals(
                "只用中文回答",
                request.get().path("messages").path(0).path("content").asText()
            );
            assertEquals("test-model", request.get().path("model").asText());
            assertFalse(request.get().path("stream").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sanitizesVendorErrorsAndNeverFollowsRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = "secret-in-vendor-error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Location", "/chat/completions");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new OpenAiChatClient(JsonMapper.builder().build());
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var auth = assertThrows(ResponseStatusException.class, () ->
                client.complete(url, "key", "m", "s", "q", null)
            );
            assertFalse(auth.getMessage().contains("secret-in-vendor-error"));
            assertEquals(502, auth.getStatusCode().value());
            var redirect = assertThrows(ResponseStatusException.class, () ->
                client.complete(url + "/redirect", "key", "m", "s", "q", null)
            );
            assertTrue(redirect.getReason().contains("302"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesToolCallsAndPreservesReasoningForNextTurn() throws Exception {
        var json = JsonMapper.builder().build();
        var received = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            received.set(
                json.readTree(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                )
            );
            var message = json
                .createObjectNode()
                .put("role", "assistant")
                .put("reasoning_content", "fixture reasoning");
            var function = json
                .createObjectNode()
                .put("name", "query_customer")
                .put("arguments", "{\"customerId\":\"C001\"}");
            var call = json
                .createObjectNode()
                .put("id", "call_1")
                .put("type", "function")
                .set("function", function);
            message.set("tool_calls", json.createArrayNode().add(call));
            var choice = json.createObjectNode().set("message", message);
            var response = json
                .createObjectNode()
                .set("choices", json.createArrayNode().add(choice));
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var definition = json.readTree(
                """
                {"type":"function","function":{"name":"query_customer","parameters":{"type":"object"}}}
                """
            );
            var result = new OpenAiChatClient(json).completeTurn(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "dummy",
                "test",
                java.util.List.of(
                    json.createObjectNode().put("role", "user").put("content", "查客户")
                ),
                json.createObjectNode(),
                java.util.List.of(definition),
                java.time.Duration.ofSeconds(5)
            );
            assertEquals("call_1", result.calls().getFirst().id());
            assertEquals("query_customer", result.calls().getFirst().name());
            assertEquals(
                "C001",
                json.readTree(result.calls().getFirst().arguments()).path("customerId").asText()
            );
            assertEquals(
                "fixture reasoning",
                result.assistantMessage().path("reasoning_content").asText()
            );
            assertEquals("auto", received.get().path("tool_choice").asText());
            assertEquals(1, received.get().path("tools").size());
        } finally {
            server.stop(0);
        }
    }
}
