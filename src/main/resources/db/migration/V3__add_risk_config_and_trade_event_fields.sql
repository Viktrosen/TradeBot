-- ==================== RISK_CONFIG ====================
CREATE TABLE IF NOT EXISTS risk_config
(
    id                VARCHAR(20) PRIMARY KEY   DEFAULT 'current',
    risk_per_trade    DOUBLE PRECISION NOT NULL DEFAULT 0.02,
    max_capital_usage DOUBLE PRECISION NOT NULL DEFAULT 0.80,
    max_position_size BIGINT           NOT NULL DEFAULT 100000,
    min_position_size BIGINT           NOT NULL DEFAULT 5000,
    max_positions     INT              NOT NULL DEFAULT 10,
    updated_at        TIMESTAMP        NOT NULL DEFAULT NOW()
);

-- Вставляем значения по умолчанию, если таблица пуста
INSERT INTO risk_config (id, risk_per_trade, max_capital_usage, max_position_size, min_position_size, max_positions,
                         updated_at)
SELECT 'current', 0.02, 0.80, 100000, 5000, 10, NOW()
WHERE NOT EXISTS (SELECT 1 FROM risk_config WHERE id = 'current');