# TradeBot — instructions for contributors

## Purpose and system boundary

This repository is the trading executor. It receives T-Invest market data, selects
instruments and strategies, applies risk controls, creates broker orders, persists
the trade lifecycle in PostgreSQL, and publishes domain events to RabbitMQ.

It is not a public client API. The Android application communicates only with
`TradebotBackend`; this service exposes the internal, Basic-Auth-protected API
consumed by that gateway.

The repository README is the source of truth for the current trade flow,
configuration, public internal endpoints, strategies, and RabbitMQ event names.
Read the relevant README section before changing any of those contracts.

## Architecture

- `api/` — internal command endpoints; do not turn them into public APIs.
- `service/TradingBotService` — orchestration of the price stream, risk checks,
  market regime and strategy signals. It must not absorb broker persistence or
  transport details.
- `strategy/` and `strategy/regime/` — pure signal and market-regime decisions.
  A strategy returns `BUY`, `SELL` or `HOLD`; order execution belongs elsewhere.
- `service/TradeDecisionService` — decides whether a signal opens or closes a
  long/short position and applies entry checks.
- `service/PositionLifecycleService` and `service/PositionProtectionService` —
  broker order lifecycle and broker stop-loss protection.
- `broker/` — the only boundary for T-Invest SDK calls.
- `repository/`, `entity/` and `src/main/resources/db/migration/` — persistence
  and Flyway schema evolution.
- `service/EventPublisherService` — publishes events for TradebotBackend. Keep
  event schemas backward-compatible when possible.

## Trading invariants

- A broker stop-loss is created for every confirmed open position. Take-profit is
  monitored by the running bot and is therefore not a broker order.
- Dynamic profit protection is bot-managed: it may move only toward profit and
  must persist its stage and exit price. Do not replace the broker SL on every
  trailing update: broker cancellation and creation are not atomic. The original
  broker SL remains the hard failure boundary while the bot is running.
- Risk, manual and emergency exits must not be delayed by AI, strategy filters or
  entry sizing rules.
- `BUY`/`SELL` are order directions, not position sides. Preserve the explicit
  `LONG`/`SHORT` side throughout persistence, reconciliation and UI contracts.
- Opening a short requires the existing short-trading, broker availability,
  margin and broker-limit checks. Never weaken them as a side effect of a feature.
- Market-regime changes are based on closed M5 candles. Do not recalculate a
  candle-only strategy on every price tick.
- A candle strategy must be deterministic from its supplied closed OHLCV
  sequence. Do not retain signal state only in process memory or derive a candle
  indicator from a streamed last price.
- The candle history cache is keyed by `instrumentUid`; current price comes from
  the `LastPrice` stream. Do not reintroduce a full candle-history request per
  tick.
- Reconciliation and broker protection paths must remain idempotent. An event may
  arrive more than once or after a restart.
- A cancellation acknowledgement for a broker SL is not proof that it did not
  execute. Before a bot-managed market close, read the final stop-order state;
  if it is executed, active or unknown, do not submit a second closing order.
- An opening timeout is an unresolved broker state, not an automatic failure.
  Mark it pending until a confirmed terminal cancellation or portfolio/order
  reconciliation resolves it. A broker-confirmed late fill must be restored,
  protected and persisted as the original bot position.
- Never adopt an external broker holding into `_openPositions` with a generated
  position id. A local position absent at the broker, or present on the opposite
  side, must be durably removed from trading with an explicit reconciliation
  event and no invented P&L. Do not blindly cancel a broker SL for an unknown
  position: it may be the last protection of a real broker holding.
- Reconciliation may enrich a legacy protection record only from a confirmed
  active broker stop order. Never infer that price locally or alter the broker
  order merely to populate display metadata.
- Trade-event audit fields are evidence, not a replacement for a tick store:
  entry context is bounded and free of secrets; MFE/MAE are written once at
  close. A restored position has incomplete excursion history and must retain
  null metrics rather than fabricated values.

## Contracts with the other repositories

- `TradebotBackend` proxies `/internal/command/*`, consumes RabbitMQ events and
  forwards DTOs without implementing trading decisions.
- The Android client consumes only TradebotBackend HTTP/STOMP/FCM contracts.
- When changing an internal response, a RabbitMQ payload, strategy identifier or
  position field, inspect both sibling repositories and preserve
  nullable/backward-compatible fields until all consumers are updated.
- Protection fields exposed for display (`brokerStopLossPrice`,
  `managedExitPrice`, `profitProtectionStage`) remain nullable and describe
  executor state; neither gateway nor client may calculate or mutate them.
- Market regimes are executor-internal inputs to automatic strategy selection.
  Do not add them to HTTP, RabbitMQ or mobile contracts without an explicit
  product decision and a versioned cross-project contract.

## Configuration and data safety

- Never commit broker tokens, Gemini keys, database/RabbitMQ passwords or API
  credentials. Use local environment files or deployment secrets.
- Do not print secrets, Authorization headers or complete AI responses in logs.
- Database changes require a new immutable Flyway migration; do not edit an
  already-applied migration.
- Treat production broker operations as non-idempotent. Do not run code that
  opens, closes or cancels real orders merely to test a change.

## Logging

- Candle diagnostics: `GET /internal/diagnostics/candles.csv` is operator-only,
  uses the existing internal Basic Auth, and exports bounded M5 history through
  `CandleHistoryReader`. Keep it read-only, separate from trading caches and client
  contracts. Dates use explicit time zones; output is UTC. Never expose broker tokens.

- `INFO` is for lifecycle, state transitions, completed orders, rescan results and
  externally useful operational events.
- Per-tick prices, repeated indicator calculations and diagnostic details belong
  to `DEBUG`; never log a `LastPrice` event at `INFO`.
- `BotOperationJournal` is an asynchronous, bounded audit trail for selected
  operational events. Use it for AI decisions, risk rejections, order/protection
  lifecycle and reconciliation; do not feed it ticks, repeated `HOLD` signals,
  indicators, secrets or complete provider responses. Its database failure must
  never block or fail a trading operation.
- Log instrument ticker/name with an identifier where that helps diagnose an
  operation, but never include credentials or complete sensitive payloads.

## Bug investigation standard

Treat a bug report as evidence of a potentially broken invariant, not merely as a
case to suppress. Before changing code, reconstruct the path through price data,
strategy, risk, order execution, persistence, reconciliation and published events
as applicable. Identify the architectural cause: an incorrect ownership boundary,
non-idempotent transition, stale state, missing transaction boundary, broken
contract, or violated long/short symmetry.

Fix the cause at its owning layer and add a regression test that describes the
invariant. Do not paper over a production symptom with a ticker-specific branch,
blind retry, swallowed exception, timing delay or UI-only workaround. If a narrow
mitigation is required for safety, keep it explicit, temporary and accompanied by
the root-cause fix or a documented follow-up.

## Verification

Run the narrowest relevant test first, then the project suite when the change is
cross-cutting:

```powershell
.\gradlew.bat test
.\gradlew.bat check
.\gradlew.bat compileKotlin
```

For a local application run use `.\gradlew.bat bootRun` only with deliberately
chosen non-production credentials and dependencies available.

## Working-tree discipline

Preserve unrelated local changes and generated directories (`build/`, `logs/`,
IDE metadata). Do not create commits unless the user explicitly asks for one.
