-- Phase 4: transport offers, school terms, bookings, impromptu (ad-hoc) pickups

CREATE TABLE transport_offers (
    id                  UUID           PRIMARY KEY,
    driver_id           UUID           NOT NULL REFERENCES driver_profiles (id) ON DELETE CASCADE,
    vehicle_id          UUID           NOT NULL REFERENCES vehicles (id),
    school_id           UUID           NOT NULL REFERENCES schools (id),
    coverage            VARCHAR(20)    NOT NULL CHECK (coverage IN ('MORNING', 'AFTERNOON', 'BOTH')),
    capacity            INTEGER        NOT NULL CHECK (capacity > 0),
    service_area        VARCHAR(500)   NOT NULL,
    morning_pickup_time VARCHAR(255),
    price_per_trip      NUMERIC(10, 2) CHECK (price_per_trip > 0),
    price_per_month     NUMERIC(10, 2) CHECK (price_per_month > 0),
    price_per_term      NUMERIC(10, 2) CHECK (price_per_term > 0),
    ad_hoc_fare         NUMERIC(10, 2) CHECK (ad_hoc_fare > 0),
    active              BOOLEAN        NOT NULL DEFAULT TRUE,
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_offers_school_active ON transport_offers (school_id, active);
CREATE INDEX idx_offers_driver ON transport_offers (driver_id);

CREATE TABLE school_terms (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(255) NOT NULL UNIQUE,
    start_date DATE         NOT NULL,
    end_date   DATE         NOT NULL,
    CHECK (end_date >= start_date)
);

CREATE TABLE bookings (
    id               UUID           PRIMARY KEY,
    child_id         UUID           NOT NULL REFERENCES children (id),
    parent_id        UUID           NOT NULL REFERENCES users (id),
    offer_id         UUID           NOT NULL REFERENCES transport_offers (id),
    plan             VARCHAR(20)    NOT NULL CHECK (plan IN ('PER_TRIP', 'MONTHLY', 'TERMLY')),
    term_id          UUID REFERENCES school_terms (id),
    start_date       DATE           NOT NULL,
    end_date         DATE           NOT NULL,
    trip_count       INTEGER,
    pickup_address   VARCHAR(500)   NOT NULL,
    pickup_latitude  DOUBLE PRECISION,
    pickup_longitude DOUBLE PRECISION,
    notes            VARCHAR(500),
    amount           NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    status           VARCHAR(20)    NOT NULL
        CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'CANCELLED', 'COMPLETED', 'EXPIRED')),
    hold_expires_at  TIMESTAMPTZ(6),
    cancel_reason    VARCHAR(255),
    cancelled_at     TIMESTAMPTZ(6),
    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ(6) NOT NULL,
    CHECK (end_date >= start_date)
);
CREATE INDEX idx_bookings_offer_status ON bookings (offer_id, status);
CREATE INDEX idx_bookings_child ON bookings (child_id);
CREATE INDEX idx_bookings_parent ON bookings (parent_id);

CREATE TABLE adhoc_requests (
    id                UUID           PRIMARY KEY,
    parent_id         UUID           NOT NULL REFERENCES users (id),
    child_id          UUID           NOT NULL REFERENCES children (id),
    school_id         UUID           NOT NULL REFERENCES schools (id),
    pickup_at         TIMESTAMPTZ(6) NOT NULL,
    dropoff_address   VARCHAR(500)   NOT NULL,
    dropoff_latitude  DOUBLE PRECISION,
    dropoff_longitude DOUBLE PRECISION,
    notes             VARCHAR(500),
    status            VARCHAR(20)    NOT NULL
        CHECK (status IN ('OPEN', 'ACCEPTED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    driver_id         UUID REFERENCES driver_profiles (id),
    offer_id          UUID REFERENCES transport_offers (id),
    fare              NUMERIC(10, 2),
    accepted_at       TIMESTAMPTZ(6),
    cancel_reason     VARCHAR(255),
    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_adhoc_school_status ON adhoc_requests (school_id, status);
CREATE INDEX idx_adhoc_parent ON adhoc_requests (parent_id);
CREATE INDEX idx_adhoc_driver ON adhoc_requests (driver_id);
