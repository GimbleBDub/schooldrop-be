-- Phase 5: payments (platform fee split) and driver payouts

CREATE TABLE payouts (
    id             UUID           PRIMARY KEY,
    driver_id      UUID           NOT NULL REFERENCES driver_profiles (id),
    amount         NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    method         VARCHAR(20)    NOT NULL,
    account        VARCHAR(512)   NOT NULL, -- AES-GCM encrypted
    status         VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED')),
    reference      VARCHAR(255),
    failure_reason VARCHAR(255),
    processed_by   UUID REFERENCES users (id),
    processed_at   TIMESTAMPTZ(6),
    created_at     TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_payouts_driver ON payouts (driver_id);
CREATE INDEX idx_payouts_status ON payouts (status);

CREATE TABLE payments (
    id                    UUID           PRIMARY KEY,
    payer_id              UUID           NOT NULL REFERENCES users (id),
    driver_id             UUID           NOT NULL REFERENCES driver_profiles (id),
    booking_id            UUID REFERENCES bookings (id),
    adhoc_request_id      UUID REFERENCES adhoc_requests (id),
    amount                NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    platform_fee          NUMERIC(10, 2) NOT NULL CHECK (platform_fee >= 0),
    driver_amount         NUMERIC(10, 2) NOT NULL CHECK (driver_amount >= 0),
    currency              VARCHAR(3)     NOT NULL,
    method                VARCHAR(20)    NOT NULL
        CHECK (method IN ('ORANGE_MONEY', 'MYZAKA', 'SMEGA', 'CARD', 'BANK_TRANSFER')),
    status                VARCHAR(20)    NOT NULL
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REFUNDED')),
    provider_reference    VARCHAR(255) UNIQUE,
    checkout_url          VARCHAR(1000),
    failure_reason        VARCHAR(255),
    earning_available_on  DATE           NOT NULL,
    payout_id             UUID REFERENCES payouts (id),
    paid_at               TIMESTAMPTZ(6),
    refunded_at           TIMESTAMPTZ(6),
    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ(6) NOT NULL,
    CHECK ((booking_id IS NULL) <> (adhoc_request_id IS NULL)),
    CHECK (platform_fee + driver_amount = amount)
);
CREATE INDEX idx_payments_booking ON payments (booking_id);
CREATE INDEX idx_payments_adhoc ON payments (adhoc_request_id);
CREATE INDEX idx_payments_payable ON payments (driver_id, earning_available_on)
    WHERE status = 'SUCCEEDED' AND payout_id IS NULL;
