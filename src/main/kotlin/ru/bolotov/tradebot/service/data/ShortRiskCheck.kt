package ru.bolotov.tradebot.service.data

import java.math.BigDecimal

sealed interface ShortRiskCheck {
    data class Allowed(val margin: MarginSnapshot) : ShortRiskCheck
    data class Rejected(val reason: String) : ShortRiskCheck
}

/**
 * Снимок маржинальных показателей счёта.
 *
 * @property fundsSufficiencyLevel Уровень достаточности средств (0.0 - 1.0)
 * @property liquidPortfolio Ликвидный портфель (рубли)
 * @property startingMargin Начальная маржа (рубли)
 * @property minimalMargin Минимальная маржа (рубли)
 * @property missingFunds Недостающие средства (положительное = не хватает, отрицательное = избыток)
 */
data class MarginSnapshot(
    val fundsSufficiencyLevel: BigDecimal,
    val liquidPortfolio: BigDecimal,
    val startingMargin: BigDecimal,
    val minimalMargin: BigDecimal,
    val missingFunds: BigDecimal
) {
    /**
     * Показывает, есть ли на счёте текущая маржинальная нагрузка.
     * При её отсутствии T-Invest может вернуть нулевой уровень достаточности средств.
     */
    fun hasCurrentMarginLoad(): Boolean =
        startingMargin > BigDecimal.ZERO || minimalMargin > BigDecimal.ZERO

    /**
     * Проверяет, достаточно ли средств на счете.
     * Отрицательное значение missingFunds означает избыток средств.
     */
    fun hasSufficientFunds(): Boolean = missingFunds <= BigDecimal.ZERO

    /**
     * Проверяет, достаточен ли запас маржи для открытия новой позиции.
     */
    fun hasSufficientMargin(minimumSufficiency: BigDecimal): Boolean =
        fundsSufficiencyLevel >= minimumSufficiency

    /**
     * Комплексная проверка возможности открытия новой позиции.
     *
     * @param minimumMarginSufficiency Минимальный допустимый уровень достаточности средств
     * @return true если можно открывать новую позицию
     */
    fun canOpenNewPosition(minimumMarginSufficiency: BigDecimal): Boolean =
        !hasCurrentMarginLoad() || // Нет маржинальной нагрузки — можно открывать
                (hasSufficientFunds() && hasSufficientMargin(minimumMarginSufficiency)) // Есть нагрузка — проверяем средства

    /**
     * Возвращает человекочитаемое описание маржинального состояния.
     */
    fun describe(): String =
        "ликвидный портфель=$liquidPortfolio, начальная маржа=$startingMargin, " +
                "минимальная маржа=$minimalMargin, уровень достаточности=$fundsSufficiencyLevel, " +
                "недостающие средства=$missingFunds"
}
