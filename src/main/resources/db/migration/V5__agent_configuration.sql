CREATE TABLE skill_definition (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    instructions TEXT NOT NULL CHECK (length(instructions) BETWEEN 1 AND 8000),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE agent_skill_grant (
    agent_id UUID NOT NULL REFERENCES agent_definition(id),
    skill_id UUID NOT NULL REFERENCES skill_definition(id),
    position INTEGER NOT NULL,
    PRIMARY KEY (agent_id, skill_id)
);
CREATE TABLE agent_execution_rule (
    agent_id UUID PRIMARY KEY REFERENCES agent_definition(id),
    rules JSONB NOT NULL
);
