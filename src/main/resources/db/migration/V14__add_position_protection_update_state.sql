ALTER TABLE position_protections
    ADD COLUMN IF NOT EXISTS replacement_stop_loss_order_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS replacement_take_profit_order_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS update_status VARCHAR(64) NOT NULL DEFAULT 'ACTIVE';
