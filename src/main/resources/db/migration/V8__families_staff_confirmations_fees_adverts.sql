-- 1. Households: a parent account is a family (owner, guardians such as a partner, helpers such as a nanny)
CREATE TABLE households (
    id         UUID           PRIMARY KEY,
    name       VARCHAR(120)   NOT NULL,
    owner_id   UUID           NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ(6) NOT NULL
);

CREATE TABLE household_members (
    id           UUID           PRIMARY KEY,
    household_id UUID           NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    user_id      UUID           NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    role         VARCHAR(20)    NOT NULL CHECK (role IN ('OWNER', 'GUARDIAN', 'HELPER')),
    joined_at    TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_household_members_household ON household_members (household_id);

-- Every existing parent becomes the owner of their own household
INSERT INTO households (id, name, owner_id, created_at)
SELECT gen_random_uuid(), COALESCE(u.last_name, 'My') || ' family', u.id, now()
FROM users u WHERE u.role = 'PARENT';
INSERT INTO household_members (id, household_id, user_id, role, joined_at)
SELECT gen_random_uuid(), h.id, h.owner_id, 'OWNER', now() FROM households h;

-- 2. School staff roles: ADMIN manages the school, enrollments and staff; STAFF (teachers, gate) confirm handovers
ALTER TABLE school_staff ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'ADMIN' CHECK (role IN ('ADMIN', 'STAFF'));
ALTER TABLE school_staff ADD COLUMN title VARCHAR(80);

-- 3. One-time invite codes to join a household or a school (stored hashed)
CREATE TABLE invites (
    id           UUID           PRIMARY KEY,
    kind         VARCHAR(20)    NOT NULL CHECK (kind IN ('HOUSEHOLD', 'SCHOOL')),
    household_id UUID REFERENCES households (id) ON DELETE CASCADE,
    school_id    UUID REFERENCES schools (id) ON DELETE CASCADE,
    role         VARCHAR(20)    NOT NULL,
    invitee_name VARCHAR(120),
    code_hash    VARCHAR(64)    NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ(6) NOT NULL,
    created_by   UUID           NOT NULL REFERENCES users (id),
    accepted_by  UUID REFERENCES users (id),
    accepted_at  TIMESTAMPTZ(6),
    revoked_at   TIMESTAMPTZ(6),
    created_at   TIMESTAMPTZ(6) NOT NULL,
    CHECK ((kind = 'HOUSEHOLD') = (household_id IS NOT NULL)),
    CHECK ((kind = 'SCHOOL') = (school_id IS NOT NULL))
);

-- 4. Handovers are confirmed by people (household / school staff), not QR codes
ALTER TABLE trip_passengers ADD COLUMN pickup_confirmed_at TIMESTAMPTZ(6);
ALTER TABLE trip_passengers ADD COLUMN pickup_confirmed_by UUID REFERENCES users (id);
ALTER TABLE trip_passengers ADD COLUMN dropoff_confirmed_at TIMESTAMPTZ(6);
ALTER TABLE trip_passengers ADD COLUMN dropoff_confirmed_by UUID REFERENCES users (id);
ALTER TABLE trip_passengers ADD COLUMN handed_to VARCHAR(120);
ALTER TABLE trip_passengers ADD COLUMN disputed_at TIMESTAMPTZ(6);
ALTER TABLE trip_passengers ADD COLUMN disputed_by UUID REFERENCES users (id);
ALTER TABLE trip_passengers ADD COLUMN dispute_note VARCHAR(500);
-- A school release was the school confirming the afternoon pickup
UPDATE trip_passengers SET pickup_confirmed_at = school_released_at, pickup_confirmed_by = school_released_by
WHERE school_released_at IS NOT NULL;
ALTER TABLE trip_passengers DROP COLUMN school_released_at;
ALTER TABLE trip_passengers DROP COLUMN school_released_by;
ALTER TABLE trip_passengers DROP COLUMN pickup_method;
ALTER TABLE children DROP COLUMN handover_code;

-- 5. Drivers pay a monthly fee to be listed
ALTER TABLE driver_profiles ADD COLUMN subscription_paid_until DATE;
CREATE TABLE driver_subscriptions (
    id           UUID           PRIMARY KEY,
    driver_id    UUID           NOT NULL REFERENCES driver_profiles (id) ON DELETE CASCADE,
    period_start DATE           NOT NULL,
    period_end   DATE           NOT NULL,
    amount       NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
    status       VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'PAID', 'CANCELLED')),
    paid_at      TIMESTAMPTZ(6),
    created_at   TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_driver_subscriptions_driver ON driver_subscriptions (driver_id);

-- 6. Adverts (drivers advertise to parents and schools; other advertisers are added by staff)
CREATE TABLE adverts (
    id               UUID           PRIMARY KEY,
    driver_id        UUID REFERENCES driver_profiles (id) ON DELETE CASCADE,
    advertiser_name  VARCHAR(120)   NOT NULL,
    title            VARCHAR(80)    NOT NULL,
    body             VARCHAR(280)   NOT NULL,
    cta_label        VARCHAR(30),
    cta_phone        VARCHAR(20),
    cta_url          VARCHAR(500),
    target_school_id UUID REFERENCES schools (id) ON DELETE CASCADE,
    status           VARCHAR(20)    NOT NULL
        CHECK (status IN ('PENDING_PAYMENT', 'PENDING_REVIEW', 'ACTIVE', 'REJECTED', 'PAUSED', 'EXPIRED')),
    review_note      VARCHAR(255),
    reviewed_by      UUID REFERENCES users (id),
    reviewed_at      TIMESTAMPTZ(6),
    starts_at        TIMESTAMPTZ(6),
    ends_at          TIMESTAMPTZ(6),
    price            NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    impressions      BIGINT         NOT NULL DEFAULT 0,
    clicks           BIGINT         NOT NULL DEFAULT 0,
    created_by       UUID           NOT NULL REFERENCES users (id),
    created_at       TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_adverts_live ON adverts (status, ends_at);

-- 7. Payments: what was paid for, and payments made directly to the driver (cash / EFT, no platform cut)
ALTER TABLE payments ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'BOOKING'
    CHECK (purpose IN ('BOOKING', 'ADHOC', 'SUBSCRIPTION', 'ADVERT'));
UPDATE payments SET purpose = 'ADHOC' WHERE adhoc_request_id IS NOT NULL;
ALTER TABLE payments ADD COLUMN offline BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payments ADD COLUMN subscription_id UUID REFERENCES driver_subscriptions (id);
ALTER TABLE payments ADD COLUMN advert_id UUID REFERENCES adverts (id);
ALTER TABLE payments DROP CONSTRAINT payments_check;
ALTER TABLE payments ADD CONSTRAINT payments_one_target
    CHECK (num_nonnulls(booking_id, adhoc_request_id, subscription_id, advert_id) = 1);
ALTER TABLE payments DROP CONSTRAINT payments_method_check;
ALTER TABLE payments ADD CONSTRAINT payments_method_check
    CHECK (method IN ('ORANGE_MONEY', 'MYZAKA', 'SMEGA', 'CARD', 'BANK_TRANSFER', 'CASH'));
ALTER TABLE driver_profiles DROP CONSTRAINT driver_profiles_payout_method_check;
ALTER TABLE driver_profiles ADD CONSTRAINT driver_profiles_payout_method_check
    CHECK (payout_method IN ('ORANGE_MONEY', 'MYZAKA', 'SMEGA', 'CARD', 'BANK_TRANSFER', 'CASH'));

ALTER TABLE bookings ADD COLUMN payment_mode VARCHAR(10) NOT NULL DEFAULT 'APP' CHECK (payment_mode IN ('APP', 'OFFLINE'));
ALTER TABLE bookings ADD COLUMN offline_paid_claimed_at TIMESTAMPTZ(6);
