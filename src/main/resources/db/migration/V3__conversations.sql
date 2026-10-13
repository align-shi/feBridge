CREATE TABLE chat_session (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent_definition(id),
    title VARCHAR(100) NOT NULL DEFAULT '新会话',
    active_request UUID,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_chat_session_agent ON chat_session(agent_id, updated_at DESC, id);
CREATE TABLE chat_turn (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES chat_session(id),
    sequence BIGSERIAL NOT NULL UNIQUE,
    user_content TEXT NOT NULL,
    assistant_content TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('pending','completed','failed')),
    error_message VARCHAR(300),
    model_name VARCHAR(100),
    provider_name VARCHAR(100),
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    total_tokens BIGINT,
    duration_millis BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);
CREATE INDEX idx_chat_turn_session ON chat_turn(session_id, sequence DESC);
