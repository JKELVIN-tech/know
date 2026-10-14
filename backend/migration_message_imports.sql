-- Review queue for detected M-Pesa messages; stores parsed fields and a hash only, never the message text.
CREATE TABLE IF NOT EXISTS message_imports (
    id SERIAL PRIMARY KEY,
    institution_id INTEGER NOT NULL REFERENCES institutions(id),
    recorded_by_id INTEGER NOT NULL REFERENCES users(id),
    code VARCHAR(30) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    direction VARCHAR(3),
    amount NUMERIC(12,2),
    fee NUMERIC(12,2),
    balance NUMERIC(12,2),
    counterparty VARCHAR(150),
    account_ref VARCHAR(60),
    bank_hint VARCHAR(40),
    message_time TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'needs_review',
    reason VARCHAR(40),
    text_hash VARCHAR(64) NOT NULL,
    transaction_id INTEGER REFERENCES transactions(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    CONSTRAINT uq_message_import_code UNIQUE (institution_id, recorded_by_id, code)
);
CREATE INDEX IF NOT EXISTS ix_message_imports_institution_id ON message_imports (institution_id);
CREATE INDEX IF NOT EXISTS ix_message_imports_recorded_by_id ON message_imports (recorded_by_id);
CREATE INDEX IF NOT EXISTS ix_message_imports_code ON message_imports (code);
CREATE INDEX IF NOT EXISTS ix_message_imports_status ON message_imports (status);
ALTER TABLE message_imports ENABLE ROW LEVEL SECURITY;
