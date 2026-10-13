package com.febridge.tool;

import static com.febridge.tool.ToolDtos.*;

import com.febridge.mcp.McpConnector;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolMapper mapper;
    private final ToolSchemas schemas;
    private final ToolNetworkGuard network;
    private final McpConnector connector;
    private final JsonMapper json;

    public ToolExecutor(
        ToolRegistry registry,
        ToolMapper mapper,
        ToolSchemas schemas,
        ToolNetworkGuard network,
        McpConnector connector,
        JsonMapper json
    ) {
        this.registry = registry;
        this.mapper = mapper;
        this.schemas = schemas;
        this.network = network;
        this.connector = connector;
        this.json = json;
    }

    public ToolResult execute(
        UUID agentId,
        UUID requestId,
        String callId,
        String alias,
        String rawArguments,
        int remainingSeconds
    ) {
        UUID auditId = UUID.randomUUID();
        ToolView tool;
        try {
            tool = registry.authorized(agentId, alias);
        } catch (ResponseStatusException exception) {
            mapper.audit(auditId, agentId, requestId, callId, null, "未授权工具", "[]");
            mapper.finishAudit(auditId, "denied", 0, "NOT_AUTHORIZED", 0);
            return error("工具不存在或未获授权");
        }
        mapper.audit(auditId, agentId, requestId, callId, tool.id(), tool.name(), "[]");
        long start = System.nanoTime();
        try {
            var server = registry.enabledServer(tool.serverId());
            var arguments = schemas.arguments(tool.inputSchema(), rawArguments);
            mapper.auditFields(auditId, json.writeValueAsString(arguments.keySet()));
            var result = network.run(
                tool.serverId(),
                Math.min(remainingSeconds, ((Number) server.get("timeout_seconds")).intValue()),
                () -> connector.call(server, tool.name(), arguments)
            );
            mapper.finishAudit(
                auditId,
                result.error() ? "failed" : "completed",
                result.content().length(),
                result.error() ? "REMOTE_TOOL_ERROR" : null,
                elapsed(start)
            );
            return result;
        } catch (ResponseStatusException exception) {
            mapper.finishAudit(
                auditId,
                exception.getStatusCode().value() == 403 ? "denied" : "failed",
                0,
                "HTTP_" + exception.getStatusCode().value(),
                elapsed(start)
            );
            return error(exception.getReason() == null ? "工具执行失败" : exception.getReason());
        } catch (RuntimeException exception) {
            mapper.finishAudit(auditId, "failed", 0, "EXECUTION_FAILED", elapsed(start));
            return error("工具执行失败");
        }
    }

    public List<ToolView> available(UUID agentId) {
        return registry.available(agentId);
    }

    private ToolResult error(String message) {
        return new ToolResult(json.createObjectNode().put("error", message).toString(), true);
    }

    private long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
