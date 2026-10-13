ALTER TABLE mcp_tool
    ALTER COLUMN enabled SET DEFAULT TRUE,
    ADD COLUMN enabled_override BOOLEAN,
    ADD COLUMN available BOOLEAN NOT NULL DEFAULT TRUE;
-- Apply the requested new default to existing tools once.
UPDATE mcp_tool SET enabled=TRUE;
