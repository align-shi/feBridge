CREATE TABLE long_memory_policy (
    owner_id UUID NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agent_definition(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    generation INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(owner_id,agent_id)
);
CREATE TABLE long_memory (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agent_definition(id) ON DELETE CASCADE,
    category VARCHAR(20) NOT NULL CHECK(category IN ('preference','default','fact')),
    memory_key VARCHAR(80) NOT NULL,
    content VARCHAR(500) NOT NULL,
    evidence VARCHAR(500),
    source_session UUID REFERENCES chat_session(id) ON DELETE SET NULL,
    source_request UUID,
    source_order BIGINT NOT NULL DEFAULT 0,
    manual BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 1,
    expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(owner_id,agent_id,memory_key)
);
CREATE TABLE long_memory_job (
    request_id UUID PRIMARY KEY REFERENCES chat_turn(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agent_definition(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    source_order BIGINT NOT NULL,
    generation INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    claim UUID,
    attempts INTEGER NOT NULL DEFAULT 0,
    leased_until TIMESTAMPTZ,
    model_name VARCHAR(100),
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    total_tokens BIGINT,
    error_message VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);
CREATE INDEX idx_long_memory_job_pending ON long_memory_job(status,created_at);
