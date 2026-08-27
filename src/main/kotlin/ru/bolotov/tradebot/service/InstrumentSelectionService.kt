package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.service.data.InstrumentSelectionFilters
import ru.bolotov.tradebot.config.InstrumentFilterProperties

private val instrumentSelectionLogger = KotlinLogging.logger {}

/** Управляет сохранёнными фильтрами и объединяет результаты рескана с открытыми позициями. */
@Service
class InstrumentSelectionService(
    private val instrumentSelector: InstrumentSelector,
    private val filterProperties: InstrumentFilterProperties,
    private val instrumentFilterConfigPersistenceService: InstrumentFilterConfigPersistenceService
) {
    /** Возвращает актуальные фильтры в виде DTO для внутреннего API. */
    fun getFilters() = InstrumentSelectionFilters(
        minDailyVolume = filterProperties.minDailyVolume,
        minVolatility = filterProperties.minVolatility,
        maxVolatility = filterProperties.maxVolatility,
        maxCount = filterProperties.maxCount
    )

    /** Загружает ранее сохранённые фильтры в используемую конфигурацию. */
    fun loadFilterConfiguration() {
        val config = instrumentFilterConfigPersistenceService.loadConfig()
        filterProperties.minDailyVolume = config.minDailyVolume
        filterProperties.minVolatility = config.minVolatility
        filterProperties.maxVolatility = config.maxVolatility
        filterProperties.maxCount = config.maxCount
    }

    /** Выполняет отбор инструментов по текущим ограничениям. */
    suspend fun selectByCurrentFilters(): List<SelectedInstrument> =
        instrumentSelector.selectTradableInstruments(
            minDailyVolume = filterProperties.minDailyVolume,
            minVolatility = filterProperties.minVolatility,
            maxVolatility = filterProperties.maxVolatility,
            maxCount = filterProperties.maxCount
        )

    /** Сохраняет в активном списке инструменты с уже открытыми позициями независимо от рескана. */
    fun mergeWithOpenPositions(
        selectedInstruments: List<SelectedInstrument>,
        openPositionInstrumentIds: Set<String>
    ): List<String> {
        val activeInstruments = (selectedInstruments.map { it.uid } + openPositionInstrumentIds).distinct()
        instrumentSelectionLogger.info { "Выбрано ${activeInstruments.size} инструментов для торговли" }
        selectedInstruments.forEach { instrument ->
            instrumentSelectionLogger.info {
                "Инструмент: ${instrument.ticker} (${instrument.instrumentType}), цена=${instrument.price}"
            }
        }
        return activeInstruments
    }

    /** Валидно сохранённые клиентом ограничения применяет к следующему рескану. */
    fun updateFilters(minDailyVolume: Long, minVolatility: Double, maxVolatility: Double, maxCount: Int) {
        filterProperties.minDailyVolume = minDailyVolume
        filterProperties.minVolatility = minVolatility
        filterProperties.maxVolatility = maxVolatility
        filterProperties.maxCount = maxCount
        instrumentFilterConfigPersistenceService.saveConfig(
            minDailyVolume = minDailyVolume,
            minVolatility = minVolatility,
            maxVolatility = maxVolatility,
            maxCount = maxCount
        )

        instrumentSelectionLogger.info {
            "Фильтры инструментов обновлены: minDailyVolume=$minDailyVolume, " +
                    "volatility=$minVolatility%..$maxVolatility%, maxCount=$maxCount"
        }
    }
}
