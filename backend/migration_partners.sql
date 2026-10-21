-- Agent (partner) program: the AGENT role, partner codes, monthly fees, fee payments with the commission fixed at the time, and payouts.
-- Run first, on its own: ALTER TYPE userrole ADD VALUE IF NOT EXISTS 'AGENT';
CREATE TABLE IF NOT EXISTS partners (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    code VARCHAR(12) NOT NULL,
    commission_rate NUMERIC(5,2) NOT NULL DEFAULT 20,
    phone VARCHAR(40),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_partners_code ON partners (code);
ALTER TABLE institutions ADD COLUMN IF NOT EXISTS partner_id INTEGER REFERENCES partners(id) ON DELETE SET NULL;
ALTER TABLE institutions ADD COLUMN IF NOT EXISTS monthly_fee NUMERIC(12,2);
CREATE INDEX IF NOT EXISTS ix_institutions_partner_id ON institutions (partner_id);
CREATE TABLE IF NOT EXISTS fee_payments (
    id SERIAL PRIMARY KEY,
    institution_id INTEGER NOT NULL REFERENCES institutions(id) ON DELETE CASCADE,
    period VARCHAR(7) NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    paid_on DATE NOT NULL,
    partner_id INTEGER REFERENCES partners(id) ON DELETE SET NULL,
    commission_rate NUMERIC(5,2),
    commission_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    note VARCHAR(200),
    recorded_by_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    voided_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_fee_payments_institution_id ON fee_payments (institution_id);
CREATE INDEX IF NOT EXISTS ix_fee_payments_partner_id ON fee_payments (partner_id);
CREATE INDEX IF NOT EXISTS ix_fee_payments_period ON fee_payments (period);
CREATE TABLE IF NOT EXISTS partner_payouts (
    id SERIAL PRIMARY KEY,
    partner_id INTEGER NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
    amount NUMERIC(12,2) NOT NULL,
    paid_on DATE NOT NULL,
    reference VARCHAR(80),
    note VARCHAR(200),
    recorded_by_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    voided_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_partner_payouts_partner_id ON partner_payouts (partner_id);
ALTER TABLE partners ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE partner_payouts ENABLE ROW LEVEL SECURITY;
