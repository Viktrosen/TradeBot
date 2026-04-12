CREATE TABLE IF NOT EXISTS trade_events
(
    id              VARCHAR(36) PRIMARY KEY,
    instrument_id   VARCHAR(50)    NOT NULL,
    instrument_name VARCHAR(50)    NOT NULL,
    direction       VARCHAR(10)    NOT NULL,
    price           DECIMAL(19, 4) NOT NULL,
    quantity        BIGINT         NOT NULL,
    total_value     DECIMAL(19, 4) NOT NULL,
    reason          VARCHAR(2000),
    explanation     VARCHAR(4000),
    timestamp       TIMESTAMP      NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    processed_at    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS portfolio_snapshots
(
    id           VARCHAR(36) PRIMARY KEY,
    total_value  DECIMAL(19, 4) NOT NULL,
    cash_balance DECIMAL(19, 4) NOT NULL,
    positions    TEXT          NOT NULL,
    timestamp    TIMESTAMP      NOT NULL
);

CREATE TABLE IF NOT EXISTS strategy_config
(
    id         VARCHAR(50) PRIMARY KEY,
    type       VARCHAR(20) NOT NULL,
    config     TEXT       NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);

CREATE INDEX idx_trade_events_timestamp ON trade_events (timestamp);
CREATE INDEX idx_trade_events_status ON trade_events (status);
CREATE INDEX idx_portfolio_snapshots_timestamp ON portfolio_snapshots (timestamp);