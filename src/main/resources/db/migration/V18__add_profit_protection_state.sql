ALTER TABLE position_protections
    ADD COLUMN IF NOT EXISTS broker_stop_loss_price NUMERIC(20, 9),
    ADD COLUMN IF NOT EXISTS managed_exit_price NUMERIC(20, 9),
    ADD COLUMN IF NOT EXISTS profit_protection_stage VARCHAR(32) NOT NULL DEFAULT 'INACTIVE';
