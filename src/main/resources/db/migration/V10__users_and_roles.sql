CREATE TABLE platform_user (
    id UUID PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(300) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    session_version BIGINT NOT NULL DEFAULT 0,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX platform_user_role_idx ON platform_user(role, enabled);
