-- Phase 6: daily trips, passenger handovers, live location, incidents/SOS

CREATE TABLE trips (
    id               UUID           PRIMARY KEY,
    offer_id         UUID REFERENCES transport_offers (id),
    adhoc_request_id UUID UNIQUE REFERENCES adhoc_requests (id),
    driver_id        UUID           NOT NULL REFERENCES driver_profiles (id),
    vehicle_id       UUID           NOT NULL REFERENCES vehicles (id),
    school_id        UUID           NOT NULL REFERENCES schools (id),
    trip_date        DATE           NOT NULL,
    leg              VARCHAR(20)    NOT NULL CHECK (leg IN ('TO_SCHOOL', 'FROM_SCHOOL')),
    status           VARCHAR(20)    NOT NULL CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    started_at       TIMESTAMPTZ(6),
    completed_at     TIMESTAMPTZ(6),
    last_latitude    DOUBLE PRECISION,
    last_longitude   DOUBLE PRECISION,
    last_location_at TIMESTAMPTZ(6),
    created_at       TIMESTAMPTZ(6) NOT NULL,
    CHECK ((offer_id IS NULL) <> (adhoc_request_id IS NULL))
);
-- One run per offer per day per leg; generated lazily, so this guards against double generation
CREATE UNIQUE INDEX uq_trips_offer_day_leg ON trips (offer_id, trip_date, leg) WHERE offer_id IS NOT NULL;
CREATE INDEX idx_trips_driver_date ON trips (driver_id, trip_date);
CREATE INDEX idx_trips_school_date ON trips (school_id, trip_date);

CREATE TABLE trip_passengers (
    id                       UUID        PRIMARY KEY,
    trip_id                  UUID        NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    child_id                 UUID        NOT NULL REFERENCES children (id),
    booking_id               UUID REFERENCES bookings (id),
    status                   VARCHAR(20) NOT NULL CHECK (status IN ('EXPECTED', 'PICKED_UP', 'DROPPED_OFF', 'ABSENT')),
    picked_up_at             TIMESTAMPTZ(6),
    pickup_method            VARCHAR(20) CHECK (pickup_method IN ('QR_SCAN', 'SCHOOL_RELEASE', 'DRIVER_MANUAL')),
    dropped_off_at           TIMESTAMPTZ(6),
    school_released_at       TIMESTAMPTZ(6),
    school_released_by       UUID REFERENCES users (id),
    kid_confirmed_pickup_at  TIMESTAMPTZ(6),
    kid_confirmed_dropoff_at TIMESTAMPTZ(6),
    CONSTRAINT uq_trip_child UNIQUE (trip_id, child_id)
);
CREATE INDEX idx_trip_passengers_child ON trip_passengers (child_id);

CREATE TABLE incidents (
    id          UUID           PRIMARY KEY,
    reporter_id UUID           NOT NULL REFERENCES users (id),
    trip_id     UUID REFERENCES trips (id),
    driver_id   UUID REFERENCES driver_profiles (id),
    type        VARCHAR(20)    NOT NULL CHECK (type IN ('SOS', 'SAFETY_CONCERN', 'COMPLAINT', 'OTHER')),
    description VARCHAR(2000)  NOT NULL,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    status      VARCHAR(20)    NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    resolution  VARCHAR(2000),
    resolved_by UUID REFERENCES users (id),
    resolved_at TIMESTAMPTZ(6),
    created_at  TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_incidents_status ON incidents (status, created_at DESC);
