CREATE TABLE bot_operation_logs (
    id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    level VARCHAR(16) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    message TEXT NOT NULL,
    instrument_id VARCHAR(255),
    instrument_name VARCHAR(255),
    position_id VARCHAR(255),
    broker_order_id VARCHAR(255),
    correlation_id VARCHAR(255),
    context_json TEXT,
    error_class VARCHAR(255),
    error_message TEXT
);

CREATE INDEX idx_bot_operation_logs_created_at
    ON bot_operation_logs (created_at DESC);
CREATE INDEX idx_bot_operation_logs_position_created_at
    ON bot_operation_logs (position_id, created_at DESC)
    WHERE position_id IS NOT NULL;
CREATE INDEX idx_bot_operation_logs_instrument_created_at
    ON bot_operation_logs (instrument_id, created_at DESC)
    WHERE instrument_id IS NOT NULL;
CREATE INDEX idx_bot_operation_logs_type_created_at
    ON bot_operation_logs (event_type, created_at DESC);
