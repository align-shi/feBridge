CREATE TABLE agent_definition (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    system_prompt TEXT NOT NULL,
    model_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agent_model_config_object CHECK (jsonb_typeof(model_config) = 'object')
);

CREATE INDEX idx_agent_created_at ON agent_definition(created_at DESC, id);
