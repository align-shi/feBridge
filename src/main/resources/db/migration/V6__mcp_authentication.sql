ALTER TABLE mcp_server
    ADD COLUMN auth_type VARCHAR(20) NOT NULL DEFAULT 'BEARER'
        CHECK (auth_type IN ('NONE','BEARER','CUSTOM_HEADER')),
    ADD COLUMN auth_header VARCHAR(100);
ALTER TABLE mcp_server ADD CONSTRAINT mcp_custom_header_required
    CHECK (auth_type <> 'CUSTOM_HEADER' OR auth_header IS NOT NULL);
