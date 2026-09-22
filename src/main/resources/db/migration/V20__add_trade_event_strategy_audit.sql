ALTER TABLE trade_events
    ADD COLUMN entry_strategy_id VARCHAR(64),
    ADD COLUMN entry_market_regime VARCHAR(32),
    ADD COLUMN signal_candle_key VARCHAR(64),
    ADD COLUMN signal_confidence NUMERIC(5, 4),
    ADD COLUMN entry_context_json TEXT,
    ADD COLUMN mfe_percent NUMERIC(12, 6),
    ADD COLUMN mae_percent NUMERIC(12, 6),
    ADD COLUMN excursion_complete BOOLEAN;

CREATE INDEX idx_trade_events_entry_strategy_id
    ON trade_events (entry_strategy_id);
