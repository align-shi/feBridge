ALTER TABLE skill_definition ADD COLUMN code VARCHAR(64);
UPDATE skill_definition SET code='skill-' || REPLACE(id::text,'-','');
ALTER TABLE skill_definition ALTER COLUMN code SET NOT NULL;
ALTER TABLE skill_definition ADD CONSTRAINT skill_definition_code_unique UNIQUE(code);
ALTER TABLE skill_definition ADD CONSTRAINT skill_definition_code_format
    CHECK (code ~ '^[a-z0-9]+(-[a-z0-9]+)*$');
