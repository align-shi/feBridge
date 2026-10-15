package com.febridge.tool;

import static com.febridge.tool.ToolDtos.*;

import com.febridge.agent.AgentService;
import com.febridge.mcp.McpAuthentication;
import com.febridge.mcp.McpAuthentication.Type;
import com.febridge.mcp.McpConnector;
import com.febridge.security.SecretCipher;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ToolRegistry {

    private final ToolMapper mapper;
    private final AgentService agents;
    private final SecretCipher cipher;
    private final OutboundPolicy policy;
    private final ToolSchemas schemas;
    private final McpConnector connector;
    private final ToolNetworkGuard network;
    private final ToolDatabase database;
    private final JsonMapper json;

    public ToolRegistry(
        ToolMapper mapper,
        AgentService agents,
        SecretCipher cipher,
        OutboundPolicy policy,
        ToolSchemas schemas,
        McpConnector connector,
        ToolNetworkGuard network,
        ToolDatabase database,
        JsonMapper json
    ) {
        this.mapper = mapper;
        this.agents = agents;
        this.cipher = cipher;
        this.policy = policy;
        this.schemas = schemas;
        this.connector = connector;
        this.network = network;
        this.database = database;
        this.json = json;
    }

    public List<ServerView> servers() {
        return mapper.servers().stream().map(this::serverView).toList();
    }

    @Transactional
    public ServerView save(UUID id, ServerInput input) {
        policy.check(input.endpoint());
        var type = input.authType() == null ? Type.BEARER : input.authType();
        String header = null;
        if (type == Type.CUSTOM_HEADER) {
            header = McpAuthentication.header(input.authHeader());
        }
        String encrypted = null;
        boolean hasNewSecret = input.token() != null && !input.token().isBlank();
        if (id == null) {
            id = UUID.randomUUID();
        } else {
            var old = serverRow(id);
            encrypted = (String) old.get("token_ciphertext");
            boolean changedTarget = !input.endpoint().equals(old.get("endpoint"));
            boolean changedAuth =
                !type.name().equals(old.get("auth_type")) ||
                !Objects.equals(header, old.get("auth_header"));
            if (
                encrypted != null &&
                !hasNewSecret &&
                type != Type.NONE &&
                (changedTarget || changedAuth)
            ) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "更换目标或鉴权方式时请重新输入鉴权值"
                );
            }
            if (changedTarget) {
                mapper.disableMissing(id, "[]");
            }
        }
        if (type == Type.NONE) {
            encrypted = null;
        } else if (hasNewSecret) {
            McpAuthentication.checkValue(input.token());
            encrypted = cipher.encrypt(input.token().strip());
        }
        if (type == Type.CUSTOM_HEADER && encrypted == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写自定义鉴权值");
        }
        mapper.saveServer(
            id,
            input.name().strip(),
            input.endpoint(),
            encrypted,
            input.enabled(),
            input.timeoutSeconds(),
            input.resultLimit(),
            type.name(),
            header
        );
        return serverView(serverRow(id));
    }

    public List<ToolView> sync(UUID id) {
        var server = enabledServer(id);
        var discovered = network.run(id, ((Number) server.get("timeout_seconds")).intValue(), () ->
            connector.discover(server)
        );
        database.discovery(id, discovered);
        return tools()
            .stream()
            .filter(tool -> tool.serverId().equals(id))
            .toList();
    }

    public List<ToolView> tools() {
        return mapper.tools().stream().map(this::toolView).toList();
    }

    @Transactional
    public ToolView updatePolicy(UUID id, ToolPolicy input) {
        var tool = requireTool(id);
        if (input.enabled()) {
            if (!((boolean) mapper.tool(id).get("available"))) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "工具已不在服务发现列表中，请先同步工具"
                );
            }
            enabledServer(tool.serverId());
            schemas.check(tool.inputSchema());
        }
        mapper.updatePolicy(id, input.enabled());
        return requireTool(id);
    }

    public List<ToolView> grants(UUID agentId) {
        agents.findById(agentId);
        return mapper.grants(agentId).stream().map(this::toolView).toList();
    }

    public List<ServerView> boundServers(UUID agentId) {
        agents.findById(agentId);
        return mapper.boundServers(agentId).stream().map(this::serverView).toList();
    }

    @Transactional
    public List<ServerView> bindServers(UUID agentId, ServerBinding input) {
        agents.lock(agentId);
        var ids = new LinkedHashSet<>(input.serverIds());
        if (ids.size() > 32 || ids.contains(null)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "智能体最多绑定 32 个 MCP 服务"
            );
        }
        var previous = boundServers(agentId)
            .stream()
            .map(ServerView::id)
            .collect(java.util.stream.Collectors.toSet());
        for (UUID id : ids) {
            var server = serverRow(id);
            if (!((boolean) server.get("enabled")) && !previous.contains(id)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "只能新增绑定已启用的 MCP 服务"
                );
            }
        }
        mapper.removeServerGrants(agentId);
        for (UUID id : ids) {
            mapper.grantServer(agentId, id);
        }
        return boundServers(agentId);
    }

    public List<ToolView> available(UUID agentId) {
        return grants(agentId).stream().filter(ToolView::enabled).toList();
    }

    public ToolView authorized(UUID agentId, String alias) {
        return available(agentId)
            .stream()
            .filter(tool -> tool.alias().equals(alias))
            .findFirst()
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.FORBIDDEN, "工具不存在或未获授权")
            );
    }

    public Map<String, Object> enabledServer(UUID id) {
        var server = serverRow(id);
        if (!((boolean) server.get("enabled"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MCP 服务已停用");
        }
        policy.check(server.get("endpoint").toString());
        return server;
    }

    public List<AuditView> audits(UUID agentId, UUID requestId) {
        agents.findById(agentId);
        return mapper
            .audits(agentId, requestId)
            .stream()
            .map(row ->
                new AuditView(
                    uuid(row, "id"),
                    uuid(row, "request_id"),
                    row.get("call_id").toString(),
                    row.get("tool_name").toString(),
                    row.get("status").toString(),
                    json.readTree(row.get("argument_fields").toString()),
                    number(row, "result_size"),
                    (String) row.get("error_code"),
                    ((Number) row.get("duration_millis")).longValue()
                )
            )
            .toList();
    }

    private Map<String, Object> serverRow(UUID id) {
        var row = mapper.server(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP 服务不存在");
        }
        return row;
    }

    private ToolView requireTool(UUID id) {
        var row = mapper.tool(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在");
        }
        return toolView(row);
    }

    private ServerView serverView(Map<String, Object> row) {
        return new ServerView(
            uuid(row, "id"),
            row.get("name").toString(),
            row.get("endpoint").toString(),
            row.get("token_ciphertext") != null,
            (boolean) row.get("enabled"),
            number(row, "timeout_seconds"),
            number(row, "result_limit"),
            Type.valueOf(row.get("auth_type").toString()),
            (String) row.get("auth_header")
        );
    }

    private ToolView toolView(Map<String, Object> row) {
        return new ToolView(
            uuid(row, "id"),
            uuid(row, "server_id"),
            row.get("server_name").toString(),
            row.get("name").toString(),
            row.get("description").toString(),
            json.readTree(row.get("input_schema").toString()),
            (boolean) row.get("read_only"),
            (boolean) row.get("enabled") &&
                (boolean) row.get("server_enabled") &&
                (boolean) row.get("available")
        );
    }

    private UUID uuid(Map<String, Object> row, String key) {
        return UUID.fromString(row.get(key).toString());
    }

    private int number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }
}
