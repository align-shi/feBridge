package com.febridge.tool;

import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OutboundPolicy {

    public URI check(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MCP 地址格式无效");
        }
        if (
            uri.getHost() == null ||
            uri.getUserInfo() != null ||
            uri.getQuery() != null ||
            uri.getFragment() != null ||
            !Set.of("http", "https").contains(uri.getScheme())
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "MCP 地址必须为无凭证、无查询参数的 HTTP(S) 地址"
            );
        }
        return uri;
    }
}
