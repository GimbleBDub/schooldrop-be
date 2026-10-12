-- Users: every account (parents, drivers, school staff, kids, platform admins)
CREATE TABLE users (
    id                       UUID         PRIMARY KEY,
    email                    VARCHAR(255) NOT NULL UNIQUE,
    password                 VARCHAR(255) NOT NULL,
    first_name               VARCHAR(255) NOT NULL,
    last_name                VARCHAR(255) NOT NULL,
    phone                    VARCHAR(255) UNIQUE,
    role                     VARCHAR(20)  NOT NULL
        CHECK (role IN ('SCHOOL', 'DRIVER', 'PARENT', 'KID', 'ADMIN')),
    parent_id                UUID REFERENCES users (id) ON DELETE CASCADE,
    profile_picture_url      VARCHAR(255),
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    account_non_locked       BOOLEAN      NOT NULL DEFAULT TRUE,
    email_verified           BOOLEAN      NOT NULL DEFAULT FALSE,
    email_verification_token VARCHAR(255),
    created_at               TIMESTAMP(6) NOT NULL,
    updated_at               TIMESTAMP(6)
);

CREATE INDEX idx_users_parent_id ON users (parent_id);
CREATE INDEX idx_users_role ON users (role);

-- Refresh tokens: one active token per user per device, stored as SHA-256 hashes
CREATE TABLE refresh_tokens (
    id         UUID           PRIMARY KEY,
    token_hash VARCHAR(64)    NOT NULL UNIQUE,
    user_id    UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id  VARCHAR(100)   NOT NULL,
    expires_at TIMESTAMPTZ(6) NOT NULL,
    revoked    BOOLEAN        NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_refresh_tokens_user_device ON refresh_tokens (user_id, device_id);
