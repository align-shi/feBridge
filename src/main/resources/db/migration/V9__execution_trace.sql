CREATE TABLE agent_run_event (
    id BIGSERIAL PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent_definition(id),
    request_id UUID NOT NULL,
    kind VARCHAR(40) NOT NULL,
    summary VARCHAR(300) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_run_event_request ON agent_run_event(agent_id,request_id,id);
