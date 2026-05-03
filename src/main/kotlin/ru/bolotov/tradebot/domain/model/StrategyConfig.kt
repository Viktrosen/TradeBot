package ru.bolotov.tradebot.domain.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "strategy_config")
class StrategyConfig(
    @Id
    var id: String = "current",

    @Enumerated(EnumType.STRING)
    var type: StrategyType,

    @Column(columnDefinition = "jsonb")
    var config: String,

    var updatedAt: Instant = Instant.now()
)

enum class StrategyType {
    SIMPLE_EMA,
    SIMPLE_RSI,
    COMPOSITE,
    VOTING,           // 🆕 стратегия голосования
    CONFIRMATION,     // 🆕 стратегия подтверждения
    CANDLESTICK       // 🆕 свечная стратегия
}