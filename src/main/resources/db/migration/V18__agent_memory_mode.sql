CREATE TABLE agent_memory_policy (
    agent_id UUID PRIMARY KEY REFERENCES agent_definition(id) ON DELETE CASCADE,
    mode VARCHAR(20) NOT NULL DEFAULT 'CONTEXT'
        CHECK(mode IN ('NONE','CONTEXT','CROSS_SESSION')),
    enabled BOOLEAN NOT NULL DEFAULT FALSE CHECK(enabled=(mode='CROSS_SESSION')),
    generation INTEGER NOT NULL DEFAULT 0
);
-- Preserve unanimous settings; conflicting per-user settings become context-only.
-- Bump the generation so pre-migration queued jobs cannot overwrite memories.
INSERT INTO agent_memory_policy(agent_id,mode,enabled,generation)
SELECT agent_id,
    CASE WHEN COUNT(DISTINCT mode)=1 THEN MIN(mode) ELSE 'CONTEXT' END,
    COUNT(DISTINCT mode)=1 AND MIN(mode)='CROSS_SESSION',
    MAX(generation)+1
FROM long_memory_policy GROUP BY agent_id;
