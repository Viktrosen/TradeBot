CREATE TABLE IF NOT EXISTS position_protections
(
    id                   VARCHAR(36) PRIMARY KEY,
    position_id          VARCHAR(36) NOT NULL UNIQUE,
    instrument_id        VARCHAR(50) NOT NULL,
    stop_loss_order_id   VARCHAR(64),
    take_profit_order_id VARCHAR(64),
    updated_at           TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_position_protections_instrument_id
    ON position_protections (instrument_id);
