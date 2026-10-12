-- Drivers can work on their own, or as part of a team / transport company: an owner, managers and
-- drivers sharing fleet vehicles. Everyone is vetted individually; the owner's profile is the business
-- (payouts, listing fee, adverts).
CREATE TABLE operators (
    id                  UUID           PRIMARY KEY,
    name                VARCHAR(160)   NOT NULL,
    kind                VARCHAR(20)    NOT NULL CHECK (kind IN ('TEAM', 'COMPANY')),
    -- CIPA registration number (companies)
    registration_number VARCHAR(60),
    owner_id            UUID           NOT NULL UNIQUE REFERENCES driver_profiles (id),
    created_at          TIMESTAMPTZ(6) NOT NULL
);

ALTER TABLE driver_profiles ADD COLUMN operator_id UUID REFERENCES operators (id);
ALTER TABLE driver_profiles ADD COLUMN operator_role VARCHAR(20) CHECK (operator_role IN ('OWNER', 'MANAGER', 'DRIVER'));
ALTER TABLE driver_profiles ADD CONSTRAINT driver_profiles_operator_role
    CHECK ((operator_id IS NULL) = (operator_role IS NULL));
-- Owners and managers who only run the business don't drive (no licence needed, never assigned to a route)
ALTER TABLE driver_profiles ADD COLUMN drives BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX idx_driver_profiles_operator ON driver_profiles (operator_id);

-- Fleet vehicles: any driver in the team can be assigned to them
ALTER TABLE vehicles ADD COLUMN operator_id UUID REFERENCES operators (id);
CREATE INDEX idx_vehicles_operator ON vehicles (operator_id);

-- Team invites
ALTER TABLE invites ADD COLUMN operator_id UUID REFERENCES operators (id) ON DELETE CASCADE;
ALTER TABLE invites DROP CONSTRAINT invites_kind_check;
ALTER TABLE invites ADD CONSTRAINT invites_kind_check CHECK (kind IN ('HOUSEHOLD', 'SCHOOL', 'TEAM'));
ALTER TABLE invites ADD CONSTRAINT invites_team_check CHECK ((kind = 'TEAM') = (operator_id IS NOT NULL));

-- Who the money belongs to, fixed when it's paid: the team owner for team drivers, else the driver.
-- A driver who later leaves a team doesn't take the team's earnings with them (and vice versa).
ALTER TABLE payments ADD COLUMN payee_id UUID REFERENCES driver_profiles (id);
UPDATE payments SET payee_id = driver_id;
ALTER TABLE payments ALTER COLUMN payee_id SET NOT NULL;
CREATE INDEX idx_payments_payee ON payments (payee_id);

-- A team's monthly listing fee covers each of its drivers
ALTER TABLE driver_subscriptions ADD COLUMN drivers INT NOT NULL DEFAULT 1 CHECK (drivers >= 1);
