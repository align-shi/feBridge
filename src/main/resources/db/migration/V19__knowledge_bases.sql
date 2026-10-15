CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES platform_user(id),
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    visibility VARCHAR(10) NOT NULL CHECK(visibility IN ('PRIVATE','SHARED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE knowledge_document (
    id UUID PRIMARY KEY,
    kb_id UUID NOT NULL REFERENCES knowledge_base(id),
    name VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending'
        CHECK(status IN ('pending','processing','ready','failed','deleting','delete_failed','deleted')),
    chunk_count INTEGER NOT NULL DEFAULT 0,
    embedding_space VARCHAR(100) NOT NULL,
    claim UUID,
    attempts INTEGER NOT NULL DEFAULT 0,
    leased_until TIMESTAMPTZ,
    error_message VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_knowledge_document_job ON knowledge_document(status,updated_at);
CREATE TABLE knowledge_chunk (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    content TEXT NOT NULL,
    UNIQUE(document_id,ordinal)
);
CREATE TABLE agent_knowledge_base (
    agent_id UUID NOT NULL REFERENCES agent_definition(id) ON DELETE CASCADE,
    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    PRIMARY KEY(agent_id,kb_id)
);
