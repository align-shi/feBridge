CREATE TABLE agent_version (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent_definition(id) ON DELETE CASCADE,
    number INTEGER NOT NULL,
    note VARCHAR(1000) NOT NULL DEFAULT '',
    snapshot JSONB NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
    fingerprint VARCHAR(64) NOT NULL,
    published_by UUID NOT NULL REFERENCES platform_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(agent_id,number),
    UNIQUE(agent_id,id)
);
CREATE TABLE agent_publication (
    agent_id UUID PRIMARY KEY REFERENCES agent_definition(id) ON DELETE CASCADE,
    version_id UUID NOT NULL,
    FOREIGN KEY(agent_id,version_id) REFERENCES agent_version(agent_id,id)
);
