-- 1. Account settings: which pushes a user wants (the in-app inbox always gets everything;
--    safety and account-security messages are always pushed)
ALTER TABLE users ADD COLUMN notify_trips BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notify_bookings BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notify_payments BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notify_news BOOLEAN NOT NULL DEFAULT TRUE;

-- Access tokens issued before this are rejected (password / email change, "sign out everywhere")
ALTER TABLE users ADD COLUMN sessions_valid_from TIMESTAMPTZ(6);
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ(6);
-- Staff accounts created (or reset) with a temporary password must choose their own first
ALTER TABLE users ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Signed-in devices: what to call each one, and when it first signed in (rotation keeps it)
ALTER TABLE refresh_tokens ADD COLUMN device_name VARCHAR(100);
ALTER TABLE refresh_tokens ADD COLUMN signed_in_at TIMESTAMPTZ(6);
ALTER TABLE refresh_tokens ADD COLUMN created_at TIMESTAMPTZ(6);
UPDATE refresh_tokens SET signed_in_at = now(), created_at = now();
ALTER TABLE refresh_tokens ALTER COLUMN signed_in_at SET NOT NULL;
ALTER TABLE refresh_tokens ALTER COLUMN created_at SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked = FALSE;

-- 3. Staff permissions: adverts get their own area, and a read-only role for the whole back office
ALTER TABLE staff_permissions DROP CONSTRAINT IF EXISTS staff_permissions_permission_check;
ALTER TABLE staff_permissions ADD CONSTRAINT staff_permissions_permission_check
    CHECK (permission IN ('SUPER_ADMIN', 'VERIFIER', 'SUPPORT', 'FINANCE', 'MARKETING', 'VIEWER'));

-- 4. Invoices, receipts, credit notes and payout statements. Numbers are sequential per series and
--    never reused; amounts and names are snapshots, so a later profile change doesn't rewrite history.
CREATE SEQUENCE receipt_number_seq;
CREATE SEQUENCE invoice_number_seq;
CREATE SEQUENCE credit_note_number_seq;
CREATE SEQUENCE statement_number_seq;

CREATE TABLE invoices (
    id                  UUID           PRIMARY KEY,
    number              VARCHAR(30)    NOT NULL UNIQUE,
    type                VARCHAR(20)    NOT NULL CHECK (type IN ('RECEIPT', 'INVOICE', 'CREDIT_NOTE', 'STATEMENT')),
    recipient_id        UUID           NOT NULL REFERENCES users (id),
    recipient_name      VARCHAR(200)   NOT NULL,
    recipient_email     VARCHAR(255),
    recipient_phone     VARCHAR(20),
    -- The family the document belongs to (parents and guardians can all see it)
    household_owner_id  UUID REFERENCES users (id),
    -- Who provided what was paid for: the driver (transport) or SchoolDrop (fees, adverts)
    supplier_name       VARCHAR(200)   NOT NULL,
    payment_id          UUID REFERENCES payments (id),
    payout_id           UUID REFERENCES payouts (id),
    credited_invoice_id UUID REFERENCES invoices (id),
    description         VARCHAR(300)   NOT NULL,
    subtotal            NUMERIC(12, 2) NOT NULL,
    tax                 NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total               NUMERIC(12, 2) NOT NULL,
    currency            VARCHAR(3)     NOT NULL DEFAULT 'BWP',
    payment_method      VARCHAR(20),
    paid_directly       BOOLEAN        NOT NULL DEFAULT FALSE,
    paid_at             TIMESTAMPTZ(6),
    notes               VARCHAR(500),
    issued_at           TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_invoices_recipient ON invoices (recipient_id, issued_at DESC);
CREATE INDEX idx_invoices_household ON invoices (household_owner_id, issued_at DESC);
-- One receipt/invoice and at most one credit note per payment, one statement per payout
CREATE UNIQUE INDEX uq_invoices_payment_doc ON invoices (payment_id) WHERE type IN ('RECEIPT', 'INVOICE');
CREATE UNIQUE INDEX uq_invoices_payment_credit ON invoices (payment_id) WHERE type = 'CREDIT_NOTE';
CREATE UNIQUE INDEX uq_invoices_payout ON invoices (payout_id) WHERE type = 'STATEMENT';

CREATE TABLE invoice_lines (
    id          UUID           PRIMARY KEY,
    invoice_id  UUID           NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    position    INT            NOT NULL,
    description VARCHAR(300)   NOT NULL,
    quantity    NUMERIC(10, 2) NOT NULL DEFAULT 1,
    unit_amount NUMERIC(12, 2) NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL
);
CREATE INDEX idx_invoice_lines_invoice ON invoice_lines (invoice_id);
