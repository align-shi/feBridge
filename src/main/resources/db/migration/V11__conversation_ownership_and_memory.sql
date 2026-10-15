ALTER TABLE chat_session ADD COLUMN owner_user_id UUID REFERENCES platform_user(id);
UPDATE chat_session SET owner_user_id=(
    SELECT id FROM platform_user WHERE role='ADMIN' AND enabled=TRUE
    ORDER BY (username='admin') DESC,created_at,id LIMIT 1
) WHERE owner_user_id IS NULL;
CREATE INDEX idx_chat_session_owner ON chat_session(owner_user_id,agent_id,updated_at DESC);

ALTER TABLE agent_run_event ADD COLUMN owner_user_id UUID REFERENCES platform_user(id);
UPDATE agent_run_event event SET owner_user_id=session.owner_user_id
FROM chat_turn turn JOIN chat_session session ON session.id=turn.session_id
WHERE event.request_id=turn.id AND event.agent_id=session.agent_id;
UPDATE agent_run_event SET owner_user_id=(
    SELECT id FROM platform_user WHERE role='ADMIN' AND enabled=TRUE
    ORDER BY (username='admin') DESC,created_at,id LIMIT 1
) WHERE owner_user_id IS NULL;
CREATE INDEX idx_run_event_owner ON agent_run_event(owner_user_id,agent_id,request_id);

CREATE TABLE conversation_summary (
    session_id UUID PRIMARY KEY REFERENCES chat_session(id) ON DELETE CASCADE,
    content JSONB NOT NULL CHECK (jsonb_typeof(content)='object'),
    covered_sequence BIGINT NOT NULL,
    covered_turns BIGINT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    input_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    model_name VARCHAR(100) NOT NULL,
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    total_tokens BIGINT,
    duration_millis BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
