-- Sign-up now collects only account type, email, phone and password; names and role details
-- come in onboarding. Existing accounts were created with full details, so they count as onboarded.
ALTER TABLE users ALTER COLUMN first_name DROP NOT NULL;
ALTER TABLE users ALTER COLUMN last_name DROP NOT NULL;
ALTER TABLE users ADD COLUMN onboarded_at TIMESTAMPTZ(6);
UPDATE users SET onboarded_at = created_at;
