-- Phase 2: driver onboarding — profile, vehicles, schools served, verification documents

CREATE TABLE driver_profiles (
    id              UUID           PRIMARY KEY,
    user_id         UUID           NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    status          VARCHAR(20)    NOT NULL
        CHECK (status IN ('PENDING_DOCUMENTS', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    status_reason   VARCHAR(255),
    omang_number    VARCHAR(512), -- AES-GCM encrypted
    licence_number  VARCHAR(512), -- AES-GCM encrypted
    licence_code    VARCHAR(10),
    licence_expiry  DATE,
    date_of_birth   DATE,
    home_area       VARCHAR(255),
    accepts_ad_hoc  BOOLEAN        NOT NULL DEFAULT FALSE,
    payout_method   VARCHAR(20) CHECK (payout_method IN ('ORANGE_MONEY', 'MYZAKA', 'SMEGA', 'CARD', 'BANK_TRANSFER')),
    payout_account  VARCHAR(512), -- AES-GCM encrypted
    reviewed_by     UUID REFERENCES users (id),
    reviewed_at     TIMESTAMPTZ(6),
    submitted_at    TIMESTAMPTZ(6),
    created_at      TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_driver_profiles_status ON driver_profiles (status);

CREATE TABLE driver_schools (
    driver_id UUID NOT NULL REFERENCES driver_profiles (id) ON DELETE CASCADE,
    school_id UUID NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    PRIMARY KEY (driver_id, school_id)
);
CREATE INDEX idx_driver_schools_school ON driver_schools (school_id);

CREATE TABLE vehicles (
    id                UUID           PRIMARY KEY,
    driver_id         UUID           NOT NULL REFERENCES driver_profiles (id) ON DELETE CASCADE,
    make              VARCHAR(255)   NOT NULL,
    model             VARCHAR(255)   NOT NULL,
    year              INTEGER,
    colour            VARCHAR(255),
    plate_number      VARCHAR(20)    NOT NULL UNIQUE,
    type              VARCHAR(20)    NOT NULL CHECK (type IN ('SEDAN', 'SUV', 'MINIBUS', 'BUS')),
    seat_capacity     INTEGER        NOT NULL CHECK (seat_capacity > 0),
    roadworthy_expiry DATE,
    insurance_expiry  DATE,
    active            BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_vehicles_driver ON vehicles (driver_id);

CREATE TABLE verification_documents (
    id                UUID           PRIMARY KEY,
    driver_id         UUID           NOT NULL REFERENCES driver_profiles (id) ON DELETE CASCADE,
    vehicle_id        UUID REFERENCES vehicles (id) ON DELETE CASCADE,
    type              VARCHAR(40)    NOT NULL,
    storage_key       VARCHAR(255)   NOT NULL,
    content_type      VARCHAR(255)   NOT NULL,
    size_bytes        BIGINT         NOT NULL,
    original_filename VARCHAR(255),
    reference_number  VARCHAR(255),
    issued_on         DATE,
    expires_on        DATE,
    status            VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    review_note       VARCHAR(255),
    reviewed_by       UUID REFERENCES users (id),
    reviewed_at       TIMESTAMPTZ(6),
    uploaded_at       TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_documents_driver ON verification_documents (driver_id);
CREATE INDEX idx_documents_expiry ON verification_documents (expires_on) WHERE status = 'APPROVED';
