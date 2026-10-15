CREATE TABLE skill_resource (
    skill_id UUID NOT NULL REFERENCES skill_definition(id) ON DELETE CASCADE,
    path VARCHAR(240) NOT NULL,
    content_base64 TEXT NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 0 AND 1048576),
    PRIMARY KEY (skill_id, path)
);
