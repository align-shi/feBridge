package com.febridge.mcp;

import java.net.http.HttpRequest;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class McpAuthentication {

    public enum Type {
        NONE,
        BEARER,
        CUSTOM_HEADER,
    }

    private static final Set<String> RESERVED = Set.of(
        "host",
        "connection",
        "content-length",
        "content-type",
        "accept",
        "transfer-encoding",
        "upgrade",
        "expect",
        "trailer",
        "te",
        "cookie",
        "origin"
    );

    private McpAuthentication() {}

    public static String header(String value) {
        if (value == null || !value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}")) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "鉴权 Key 必须为合法 HTTP 请求头名称"
            );
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (
            RESERVED.contains(lower) ||
            lower.startsWith("mcp-") ||
            lower.startsWith("sec-") ||
            lower.startsWith("proxy-")
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "鉴权 Key 不能覆盖连接或 MCP 协议请求头"
            );
        }
        return value;
    }

    public static void checkValue(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 || character > 126) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "鉴权值必须为可打印 ASCII 字符"
                );
            }
        }
    }

    public static void apply(HttpRequest.Builder request, Type type, String name, String value) {
        if (type == Type.NONE || value == null) {
            return;
        }
        checkValue(value);
        if (type == Type.BEARER) {
            request.header("Authorization", "Bearer " + value);
        } else {
            request.header(header(name), value);
        }
    }
}
