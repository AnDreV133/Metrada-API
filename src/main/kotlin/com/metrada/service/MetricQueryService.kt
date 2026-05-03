// service/MetricQueryService.kt
package com.metrada.service

import com.metrada.model.MatrixResultModel
import com.metrada.model.MetricDataModel
import com.metrada.model.MetricResponseModel
import com.metrada.model.VectorResultModel
import com.metrada.service.parser.Engine
import com.metrada.service.parser.Querier
import com.metrada.service.parser.Value
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class MetricQueryService(
    private val engine: Engine,
    private val querier: Querier,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Мгновенный запрос – возвращает ответ в формате Prometheus API.
     * @param query PromQL выражение
     * @param time момент времени (если null, используется текущее время)
     */
    fun queryInstant(query: String, time: Instant? = null): MetricResponseModel {
        val evalTimeMillis = (time ?: Instant.now()).toEpochMilli()
        return runBlocking {
            try {
                val result = engine.newInstantQuery(querier, query, evalTimeMillis)
                val data = convertToPrometheusData(result.value)
                MetricResponseModel(status = "success", data = data)
            } catch (e: Exception) {
                MetricResponseModel(
                    status = "error",
                    error = e.message,
                    errorType = "execution_error"
                )
            }
        }
    }

    /**
     * Диапазонный запрос – возвращает ответ в формате Prometheus API (matrix).
     * @param query PromQL выражение
     * @param start начальное время
     * @param end конечное время
     * @param step шаг (например, "15s")
     */
    fun queryRange(query: String, start: Instant, end: Instant, step: Duration): MetricResponseModel {
        val startMillis = start.toEpochMilli()
        val endMillis = end.toEpochMilli()
        val stepMillis = step.toMillis()
        return runBlocking {
            try {
                val result = engine.newRangeQuery(querier, query, startMillis, endMillis, stepMillis)
                val data = convertToPrometheusData(result.value)
                MetricResponseModel(status = "success", data = data)
            } catch (e: Exception) {
                MetricResponseModel(
                    status = "error",
                    error = e.message,
                    errorType = "execution_error"
                )
            }
        }
    }

    private fun convertToPrometheusData(value: Value): MetricDataModel {
        return when (value) {
            is Value.Vector -> {
                val results = value.samples.map { sample ->
                    VectorResultModel(
                        metric = sample.metric.values,        // Map<String,String>
                        value = listOf(sample.t / 1000.0, sample.f)
                    )
                }
                MetricDataModel(resultType = "vector", result = results)
            }

            is Value.Matrix -> {
                val results = value.series.map { series ->
                    val values = series.points.map { point ->
                        listOf(point.t / 1000.0, point.f)
                    }
                    MatrixResultModel(
                        metric = series.metric.values,
                        values = values
                    )
                }
                MetricDataModel(resultType = "matrix", result = results)
            }

            is Value.Scalar -> {
                MetricDataModel(
                    resultType = "scalar",
                    result = listOf(listOf(value.t / 1000.0, value.v))
                )
            }

            is Value.String -> {
                MetricDataModel(
                    resultType = "string",
                    result = listOf(listOf(value.t / 1000.0, value.v))
                )
            }
        }
    }
}