-- Cost tracking: running average cost per stock item, the cost fixed on each sale, and the cost carried by a transfer.
ALTER TABLE stock_items ADD COLUMN IF NOT EXISTS avg_cost NUMERIC(12,4);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS cost_total NUMERIC(12,2);
ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS unit_cost NUMERIC(12,4);
