-- Per-item low stock alert level; the app also adds this column itself on start-up
ALTER TABLE stock_items ADD COLUMN IF NOT EXISTS low_stock_threshold NUMERIC(12,2);
