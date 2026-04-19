-- V2__add_pnl_and_event_type.sql
ALTER TABLE trade_events
    ADD COLUMN IF NOT EXISTS pnl DECIMAL(20, 2) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS event_type VARCHAR(10) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS position_id VARCHAR(36) DEFAULT NULL;

-- Заполнить event_type для существующих записей
UPDATE trade_events SET event_type = 'OPEN' WHERE reason NOT IN ('CLOSE', 'SIGNAL_CLOSE', 'EMERGENCY_CLOSE');
UPDATE trade_events SET event_type = 'CLOSE' WHERE reason IN ('CLOSE', 'SIGNAL_CLOSE', 'EMERGENCY_CLOSE');

-- Индексы
CREATE INDEX IF NOT EXISTS idx_trade_event_pnl ON trade_events(pnl);
CREATE INDEX IF NOT EXISTS idx_trade_event_event_type ON trade_events(event_type);
CREATE INDEX IF NOT EXISTS idx_trade_event_position_id ON trade_events(position_id);