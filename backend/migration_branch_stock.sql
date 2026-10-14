-- Branch-owned stock and the exchange (transfer) panel; transfers never create transactions or journal entries.
ALTER TABLE stock_items ADD COLUMN IF NOT EXISTS branch_id INTEGER REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS ix_stock_items_branch_id ON stock_items (branch_id);

CREATE TABLE IF NOT EXISTS stock_transfers (
    id SERIAL PRIMARY KEY,
    institution_id INTEGER NOT NULL REFERENCES institutions(id),
    from_branch_id INTEGER NOT NULL REFERENCES users(id),
    to_branch_id INTEGER NOT NULL REFERENCES users(id),
    source_item_id INTEGER NOT NULL REFERENCES stock_items(id),
    dest_item_id INTEGER REFERENCES stock_items(id),
    item_name VARCHAR(150) NOT NULL,
    category_id INTEGER REFERENCES product_categories(id),
    unit_price NUMERIC(12,2),
    quantity NUMERIC(12,2) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'pending',
    note VARCHAR(200),
    created_by_id INTEGER NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolved_by_id INTEGER REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS ix_stock_transfers_institution_id ON stock_transfers (institution_id);
CREATE INDEX IF NOT EXISTS ix_stock_transfers_from_branch_id ON stock_transfers (from_branch_id);
CREATE INDEX IF NOT EXISTS ix_stock_transfers_to_branch_id ON stock_transfers (to_branch_id);
CREATE INDEX IF NOT EXISTS ix_stock_transfers_status ON stock_transfers (status);
ALTER TABLE stock_transfers ENABLE ROW LEVEL SECURITY;

-- Existing shared stock goes to the branch account when an institution has exactly one; otherwise the owner assigns it.
UPDATE stock_items s SET branch_id = u.id
FROM (SELECT institution_id, MIN(id) AS id FROM users WHERE role = 'STAFF' AND staff_type = 'GENERAL' AND is_active GROUP BY institution_id HAVING COUNT(*) = 1) u
WHERE s.institution_id = u.institution_id AND s.branch_id IS NULL;
