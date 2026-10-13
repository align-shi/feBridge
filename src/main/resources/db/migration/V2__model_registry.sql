CREATE TABLE model_provider (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    provider_type VARCHAR(20) NOT NULL CHECK (provider_type IN ('DEEPSEEK', 'ZHIPU')),
    base_url VARCHAR(300) NOT NULL,
    api_key_ciphertext TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE model_definition (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES model_provider(id),
    model_code VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    supports_tools BOOLEAN NOT NULL DEFAULT FALSE,
    default_parameters JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(default_parameters) = 'object'),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(provider_id, model_code)
);
ALTER TABLE agent_definition ADD COLUMN model_id UUID REFERENCES model_definition(id);
CREATE INDEX idx_agent_model ON agent_definition(model_id);
