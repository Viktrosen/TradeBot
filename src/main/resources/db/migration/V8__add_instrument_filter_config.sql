CREATE TABLE IF NOT EXISTS instrument_filter_config
(
    id               VARCHAR(20) PRIMARY KEY   DEFAULT 'current',
    min_daily_volume BIGINT           NOT NULL DEFAULT 100000,
    min_volatility   DOUBLE PRECISION NOT NULL DEFAULT 3.0,
    max_volatility   DOUBLE PRECISION NOT NULL DEFAULT 15.0,
    max_count        INT              NOT NULL DEFAULT 10,
    updated_at       TIMESTAMP        NOT NULL DEFAULT NOW()
);

INSERT INTO instrument_filter_config (id, min_daily_volume, min_volatility, max_volatility, max_count, updated_at)
SELECT 'current', 100000, 3.0, 15.0, 10, NOW()
WHERE NOT EXISTS (SELECT 1 FROM instrument_filter_config WHERE id = 'current');
