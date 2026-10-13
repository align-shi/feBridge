package com.febridge.mcp;

import com.febridge.security.SecretCipher;
import com.febridge.tool.OutboundPolicy;
import com.febridge.tool.ToolDtos.ToolResult;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class McpConnector {

    private final OutboundPolicy policy;
    private final SecretCipher cipher;
    private final JsonMapper json;

    public McpConnector(OutboundPolicy policy, SecretCipher cipher, JsonMapper json) {
        this.policy = policy;
        this.cipher = cipher;
        this.json = json;
    }

    private McpSyncClient open(Map<String, Object> server) {
        var uri = policy.check(server.get("endpoint").toString());
        var request = HttpRequest.newBuilder();
        var authType = McpAuthentication.Type.valueOf(
            server.getOrDefault("auth_type", "BEARER").toString()
        );
        if (authType != McpAuthentication.Type.NONE && server.get("token_ciphertext") != null) {
            McpAuthentication.apply(
                request,
                authType,
                (String) server.get("auth_header"),
                cipher.decrypt(server.get("token_ciphertext").toString())
            );
        }
        int seconds = ((Number) server.get("timeout_seconds")).intValue();
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        var transport = HttpClientStreamableHttpTransport.builder(base)
            .endpoint(path)
            .clientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER))
            .requestBuilder(request)
            .connectTimeout(Duration.ofSeconds(Math.min(5, seconds)))
            .openConnectionOnStartup(false)
            .build();
        return McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(seconds))
            .initializationTimeout(Duration.ofSeconds(seconds))
            .enableCallToolSchemaCaching(false)
            .clientInfo(new McpSchema.Implementation("feBridge", "0.1.0"))
            .build();
    }

    public List<McpSchema.Tool> discover(Map<String, Object> server) {
        try (var client = open(server)) {
            client.initialize();
            var tools = new ArrayList<McpSchema.Tool>();
            String cursor = null;
            for (int page = 0; page < 10; page++) {
                var result = client.listTools(cursor);
                tools.addAll(result.tools());
                if (tools.size() > 200) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "MCP 工具数量超过 200"
                    );
                }
                cursor = result.nextCursor();
                if (cursor == null || cursor.isBlank()) {
                    return tools;
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MCP 分页过多");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "MCP 连接或工具发现失败，请检查服务、鉴权和协议"
            );
        }
    }

    public ToolResult call(Map<String, Object> server, String name, Map<String, Object> arguments) {
        try (var client = open(server)) {
            client.initialize();
            var result = client.callTool(new McpSchema.CallToolRequest(name, arguments));
            int limit = ((Number) server.get("result_limit")).intValue();
            var text = new StringBuilder();
            if (result.structuredContent() != null) {
                text.append(json.writeValueAsString(result.structuredContent()));
            } else if (result.content() != null) {
                for (var content : result.content()) {
                    if (content instanceof McpSchema.TextContent item) {
                        text.append(item.text()).append('\n');
                    } else {
                        throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "当前工具网关只接收文本和结构化结果"
                        );
                    }
                    if (text.length() > limit) {
                        throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "工具结果超过大小限制"
                        );
                    }
                }
            }
            if (text.length() > limit) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "工具结果超过大小限制");
            }
            return new ToolResult(text.toString(), Boolean.TRUE.equals(result.isError()));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MCP 工具执行失败或超时");
        }
    }
}
