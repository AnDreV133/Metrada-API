package com.metrada.controller

import com.metrada.model.MatrixResultModel
import com.metrada.model.MetricDataModel
import com.metrada.model.MetricResponseModel
import com.metrada.model.VectorResultModel
import com.metrada.service.MetricQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class QueryController(
    private val metricQueryService: MetricQueryService,
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
        @RequestParam(required = false) time: String?,
    ): MetricResponseModel {
        val evalTime = when {
            time == null -> Instant.now()
            time.matches(Regex("\\d+(\\.\\d+)?")) -> Instant.ofEpochSecond(time.toDouble().toLong())
            else -> Instant.parse(time)
        }
        return metricQueryService.queryInstant(cleanQuery(query), evalTime)
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
        @RequestParam start: String,
        @RequestParam end: String,
        @RequestParam step: String,
    ): MetricResponseModel {
        val startTime = parseTime(start)
        val endTime = parseTime(end)
        val stepDuration = parseDuration(step)
        return metricQueryService.queryRange(cleanQuery(query), startTime, endTime, stepDuration)
    }

    private fun parseTime(timeStr: String): Instant = when {
        timeStr.matches(Regex("\\d+(\\.\\d+)?")) -> Instant.ofEpochSecond(timeStr.toDouble().toLong())
        else -> Instant.parse(timeStr)
    }


    private fun parseDuration(step: String): Duration {
        // Простейший парсер для строк вида "15s", "1m", "2h", "1d"
        val value = step.trim().dropLast(1).toLongOrNull()
            ?: throw IllegalArgumentException("Invalid step format: $step")
        return when (step.last()) {
            's' -> Duration.ofSeconds(value)
            'm' -> Duration.ofMinutes(value)
            'h' -> Duration.ofHours(value)
            'd' -> Duration.ofDays(value)
            else -> Duration.ofSeconds(step.toLongOrNull() ?: error("unsuported duration"))
        }
    }

    fun cleanQuery(query: String): String {
        return query.replace("\uFEFF", "")
    }
}