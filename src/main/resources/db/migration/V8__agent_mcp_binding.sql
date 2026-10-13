CREATE TABLE agent_mcp_grant (
    agent_id UUID NOT NULL REFERENCES agent_definition(id),
    server_id UUID NOT NULL REFERENCES mcp_server(id),
    PRIMARY KEY(agent_id,server_id)
);
INSERT INTO agent_mcp_grant(agent_id,server_id)
SELECT DISTINCT g.agent_id,t.server_id FROM agent_tool_grant g JOIN mcp_tool t ON t.id=g.tool_id;
