ALTER TABLE long_memory_policy ADD COLUMN mode VARCHAR(20);
UPDATE long_memory_policy SET mode=CASE WHEN enabled THEN 'CROSS_SESSION' ELSE 'CONTEXT' END;
ALTER TABLE long_memory_policy ALTER COLUMN mode SET DEFAULT 'CONTEXT';
ALTER TABLE long_memory_policy ALTER COLUMN mode SET NOT NULL;
ALTER TABLE long_memory_policy ALTER COLUMN enabled SET DEFAULT FALSE;
ALTER TABLE long_memory_policy ADD CONSTRAINT long_memory_mode_check
    CHECK (mode IN ('NONE','CONTEXT','CROSS_SESSION'));
ALTER TABLE long_memory_policy ADD CONSTRAINT long_memory_mode_enabled_check
    CHECK (enabled = (mode='CROSS_SESSION'));
