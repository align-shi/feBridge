package com.febridge.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.febridge.mcp.McpConnector;
import com.febridge.security.SecretCipher;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class ToolGatewayTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void allowsBusinessTargetsAndRejectsInvalidUrls() {
        var policy = new OutboundPolicy();
        assertEquals("/mcp", policy.check("http://127.0.0.1:8090/mcp").getPath());
        assertEquals("10.0.0.8", policy.check("http://10.0.0.8:3101/mcp").getHost());
        assertEquals("/mcp", policy.check("https://business.example/mcp").getPath());
        assertThrows(ResponseStatusException.class, () ->
            policy.check("https://user:secret@business.example/mcp")
        );
        assertThrows(ResponseStatusException.class, () ->
            policy.check("https://business.example/mcp?token=x")
        );
    }

    @Test
    void validatesRequiredFieldsTypesAndUnknownFields() {
        var schemas = new ToolSchemas(json);
        var schema = json.readTree(
            """
            {"type":"object","properties":{"customerId":{"type":"string"}},
             "required":["customerId"],"additionalProperties":false}
            """
        );
        assertEquals(
            "C001",
            schemas.arguments(schema, "{\"customerId\":\"C001\"}").get("customerId")
        );
        assertThrows(ResponseStatusException.class, () -> schemas.arguments(schema, "{}"));
        assertThrows(ResponseStatusException.class, () ->
            schemas.arguments(schema, "{\"customerId\":1}")
        );
        assertThrows(ResponseStatusException.class, () ->
            schemas.arguments(schema, "{\"customerId\":\"C001\",\"url\":\"x\"}")
        );
        assertThrows(ResponseStatusException.class, () ->
            schemas.check(
                json.readTree(
                    """
                    {"type":"object","properties":{"x":{"$ref":"https://remote.example/schema"}}}
                    """
                )
            )
        );
    }

    @Test
    void usesRealMcpProtocolAndServerSideCredential() throws Exception {
        var expectedHeader = new java.util.concurrent.atomic.AtomicReference<String>(
            "Authorization"
        );
        var expectedValue = new java.util.concurrent.atomic.AtomicReference<String>(
            "Bearer test-token"
        );
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if (
                !expectedValue
                    .get()
                    .equals(exchange.getRequestHeaders().getFirst(expectedHeader.get()))
            ) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            var request = json.readTree(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
            );
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            var response = json
                .createObjectNode()
                .put("jsonrpc", "2.0")
                .set("id", request.get("id"));
            var result = switch (request.path("method").asText()) {
                case "initialize" -> json.readTree(
                    """
                    {"protocolVersion":"2025-11-25","capabilities":{"tools":{}},
                     "serverInfo":{"name":"fixture","version":"1.0"}}
                    """
                );
                case "tools/list" -> json.readTree(
                    """
                    {"tools":[{"name":"query_customer","description":"Read fixture",
                     "inputSchema":{"type":"object","properties":{"customerId":{"type":"string"}}}}]}
                    """
                );
                case "tools/call" -> json.readTree(
                    """
                    {"content":[{"type":"text","text":"customer C001"}],"isError":false}
                    """
                );
                default -> json.createObjectNode();
            };
            response.set("result", result);
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            var cipher = mock(SecretCipher.class);
            when(cipher.decrypt("encrypted")).thenReturn("test-token");
            var connector = new McpConnector(new OutboundPolicy(), cipher, json);
            var config = Map.<String, Object>of(
                "endpoint",
                origin + "/mcp",
                "token_ciphertext",
                "encrypted",
                "timeout_seconds",
                5,
                "result_limit",
                1024
            );
            assertEquals("query_customer", connector.discover(config).getFirst().name());
            var answer = connector.call(config, "query_customer", Map.of("customerId", "C001"));
            assertFalse(answer.error());
            assertTrue(answer.content().contains("C001"));
            expectedHeader.set("X-API-Key");
            expectedValue.set("test-token");
            var custom = new java.util.HashMap<String, Object>(config);
            custom.put("auth_type", "CUSTOM_HEADER");
            custom.put("auth_header", "X-API-Key");
            assertEquals("query_customer", connector.discover(custom).getFirst().name());
            assertFalse(
                connector.call(custom, "query_customer", Map.of("customerId", "C001")).error()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blocksHeaderInjectionAndProtocolOverrides() {
        assertThrows(ResponseStatusException.class, () ->
            com.febridge.mcp.McpAuthentication.header("X-Key\r\nInjected")
        );
        assertThrows(ResponseStatusException.class, () ->
            com.febridge.mcp.McpAuthentication.header("Mcp-Session-Id")
        );
        assertThrows(ResponseStatusException.class, () ->
            com.febridge.mcp.McpAuthentication.header("Content-Type")
        );
        assertThrows(ResponseStatusException.class, () ->
            com.febridge.mcp.McpAuthentication.checkValue("value\r\nInjected")
        );
        assertEquals("api-key", com.febridge.mcp.McpAuthentication.header("api-key"));
    }
}
