-- Email address on accounts (unique per account) and single-use password reset tokens (only a hash is stored).
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(254);
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email ON users (lower(email)) WHERE email IS NOT NULL;

CREATE TABLE IF NOT EXISTS password_resets (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    requested_ip VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_password_resets_hash ON password_resets (token_hash);
CREATE INDEX IF NOT EXISTS ix_password_resets_user_id ON password_resets (user_id);
ALTER TABLE password_resets ENABLE ROW LEVEL SECURITY;
