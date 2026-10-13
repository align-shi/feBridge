CREATE TABLE mcp_server (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    endpoint VARCHAR(500) NOT NULL,
    token_ciphertext TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    timeout_seconds INTEGER NOT NULL DEFAULT 15 CHECK (timeout_seconds BETWEEN 1 AND 30),
    result_limit INTEGER NOT NULL DEFAULT 16000 CHECK (result_limit BETWEEN 1024 AND 32768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE mcp_tool (
    id UUID PRIMARY KEY,
    server_id UUID NOT NULL REFERENCES mcp_server(id),
    name VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    input_schema JSONB NOT NULL,
    read_only BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(server_id, name)
);
CREATE TABLE agent_tool_grant (
    agent_id UUID NOT NULL REFERENCES agent_definition(id),
    tool_id UUID NOT NULL REFERENCES mcp_tool(id),
    PRIMARY KEY(agent_id, tool_id)
);
CREATE TABLE tool_call_audit (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent_definition(id),
    request_id UUID NOT NULL,
    call_id VARCHAR(200) NOT NULL,
    tool_id UUID REFERENCES mcp_tool(id),
    tool_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('running','completed','failed','denied')),
    argument_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    result_size INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(50),
    duration_millis BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(agent_id, request_id, call_id)
);
CREATE INDEX idx_tool_audit_request ON tool_call_audit(agent_id, request_id, created_at);
