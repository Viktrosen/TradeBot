ALTER TABLE trade_events
    ADD COLUMN position_side VARCHAR(16) NOT NULL DEFAULT 'LONG';

UPDATE trade_events
SET position_side = CASE
    WHEN direction = 'SELL' AND event_type = 'OPEN' THEN 'SHORT'
    ELSE 'LONG'
END;
