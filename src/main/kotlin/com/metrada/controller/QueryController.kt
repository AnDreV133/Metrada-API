package com.metrada.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

import com.metrada.model.MatrixResult
import com.metrada.model.MetricData
import com.metrada.model.MetricResponse
import com.metrada.model.VectorResult
import com.metrada.service.MetricQueryService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/v1")
class QueryController(
    private val metricQueryService: MetricQueryService
) {

    /**
     * Instant query – возвращает значение на указанный момент времени.
     * GET /v1/query?query=<expr>&time=<timestamp>
     *
     * @param query PromQL выражение (обязательный)
     * @param time момент времени в формате RFC3339 или Unix timestamp (опциональный, по умолчанию now)
     */
    @GetMapping("/query")
    fun instantQuery(
        @RequestParam query: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) time: Instant?
    ): MetricResponse {
        val evalTime = time ?: Instant.now()
        val result = metricQueryService.queryInstant(query, evalTime)

        return buildInstantResponse(result, evalTime)
    }

    /**
     * Range query – возвращает матрицу значений на интервале времени.
     * GET /v1/query_range?query=<expr>&start=<timestamp>&end=<timestamp>&step=<duration>
     *
     * @param query PromQL выражение
     * @param start начало интервала
     * @param end конец интервала
     * @param step шаг, например "15s", "1m", "1h"
     */
    @GetMapping("/query_range")
    fun rangeQuery(
        @RequestParam query: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) start: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) end: Instant,
        @RequestParam step: String
    ): MetricResponse {
        val stepDuration = parseDuration(step)
        val rangeResults = metricQueryService.queryRange(query, start, end, stepDuration)

        return buildRangeResponse(rangeResults)
    }

    // ==================== Формирование ответов ====================

    private fun buildInstantResponse(result: Any, evalTime: Instant): MetricResponse {
        return when (result) {
            is Double -> {
                // Скаляр: [timestamp, value]
                val scalarValue = listOf(evalTime.epochSecond, result)
                MetricResponse(
                    data = MetricData(
                        resultType = "scalar",
                        result = scalarValue
                    )
                )
            }
            is List<*> -> {
                // Вектор: список объектов с метрикой и значением
                val vector = listOf(
                    VectorResult(
                        metric = emptyMap(), // метки можно извлечь из контекста, но для простоты пустые
                        value = listOf(evalTime.epochSecond, result.lastOrNull() ?: Double.NaN)
                    )
                )
                MetricResponse(
                    data = MetricData(
                        resultType = "vector",
                        result = vector
                    )
                )
            }
            else -> {
                // Неподдерживаемый тип – возвращаем ошибку
                MetricResponse(
                    status = "error",
                    data = MetricData(
                        resultType = "string",
                        result = "Unsupported result type: ${result::class.simpleName}"
                    )
                )
            }
        }
    }

    private fun buildRangeResponse(results: List<MetricQueryService.RangeQueryResult>): MetricResponse {
        val matrix = listOf(
            MatrixResult(
                metric = emptyMap(),
                values = results.map { listOf(it.timestamp.epochSecond, it.value) }
            )
        )
        return MetricResponse(
            data = MetricData(
                resultType = "matrix",
                result = matrix
            )
        )
    }

    private fun parseDuration(step: String): Duration {
        // Простейший парсер для строк вида "15s", "1m", "2h", "1d"
        val value = step.dropLast(1).toLongOrNull()
            ?: throw IllegalArgumentException("Invalid step format: $step")
        return when (step.last()) {
            's' -> Duration.ofSeconds(value)
            'm' -> Duration.ofMinutes(value)
            'h' -> Duration.ofHours(value)
            'd' -> Duration.ofDays(value)
            else -> throw IllegalArgumentException("Unsupported step suffix: ${step.last()}")
        }
    }
}