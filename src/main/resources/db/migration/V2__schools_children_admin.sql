-- Phase 1: schools, children, kid pairing; admin foundation (staff permissions, audit log, notifications)

CREATE TABLE schools (
    id                  UUID           PRIMARY KEY,
    name                VARCHAR(255)   NOT NULL,
    registration_number VARCHAR(255),
    address             VARCHAR(255)   NOT NULL,
    city                VARCHAR(255)   NOT NULL,
    latitude            DOUBLE PRECISION,
    longitude           DOUBLE PRECISION,
    phone               VARCHAR(255),
    email               VARCHAR(255),
    status              VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED', 'SUSPENDED')),
    status_reason       VARCHAR(255),
    reviewed_by         UUID REFERENCES users (id),
    reviewed_at         TIMESTAMPTZ(6),
    created_at          TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_schools_status ON schools (status);

CREATE TABLE school_staff (
    id         UUID           PRIMARY KEY,
    school_id  UUID           NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    user_id    UUID           NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_school_staff_school ON school_staff (school_id);

CREATE TABLE children (
    id                      UUID           PRIMARY KEY,
    parent_id               UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id               UUID REFERENCES schools (id),
    first_name              VARCHAR(255)   NOT NULL,
    last_name               VARCHAR(255)   NOT NULL,
    date_of_birth           DATE,
    grade                   VARCHAR(255),
    student_number          VARCHAR(255),
    special_needs           VARCHAR(1000),
    enrollment_status       VARCHAR(20) CHECK (enrollment_status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    enrollment_note         VARCHAR(255),
    handover_code           VARCHAR(64)    NOT NULL UNIQUE,
    kid_user_id             UUID UNIQUE REFERENCES users (id) ON DELETE SET NULL,
    pairing_code_hash       VARCHAR(64) UNIQUE,
    pairing_code_expires_at TIMESTAMPTZ(6),
    archived_at             TIMESTAMPTZ(6), -- "deleted" by the parent but kept for trip/booking history
    created_at              TIMESTAMPTZ(6) NOT NULL,
    updated_at              TIMESTAMPTZ(6)
);
CREATE INDEX idx_children_parent ON children (parent_id);
CREATE INDEX idx_children_school_status ON children (school_id, enrollment_status);

CREATE TABLE staff_permissions (
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    permission VARCHAR(20) NOT NULL CHECK (permission IN ('SUPER_ADMIN', 'VERIFIER', 'SUPPORT', 'FINANCE')),
    PRIMARY KEY (user_id, permission)
);

CREATE TABLE audit_logs (
    id          UUID           PRIMARY KEY,
    actor_id    UUID REFERENCES users (id),
    action      VARCHAR(64)    NOT NULL,
    target_type VARCHAR(64)    NOT NULL,
    target_id   UUID,
    details     VARCHAR(2000),
    created_at  TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_audit_target ON audit_logs (target_type, target_id);
CREATE INDEX idx_audit_created ON audit_logs (created_at DESC);

CREATE TABLE notifications (
    id           UUID           PRIMARY KEY,
    user_id      UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         VARCHAR(40)    NOT NULL,
    title        VARCHAR(255)   NOT NULL,
    body         VARCHAR(1000)  NOT NULL,
    reference_id UUID,
    read_at      TIMESTAMPTZ(6),
    created_at   TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);

CREATE TABLE device_tokens (
    id         UUID           PRIMARY KEY,
    user_id    UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token      VARCHAR(512)   NOT NULL UNIQUE,
    platform   VARCHAR(20)    NOT NULL,
    updated_at TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);
