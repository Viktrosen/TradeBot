ALTER TABLE trade_events
    ADD COLUMN IF NOT EXISTS broker_order_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS execution_status VARCHAR(100),
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS broker_order_state TEXT;
