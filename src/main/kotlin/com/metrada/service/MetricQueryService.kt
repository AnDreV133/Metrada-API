package com.metrada.service

import com.metrada.model.MetricReference
import com.metrada.repository.MetricRepository
import com.metrada.util.math.ExpressionParser
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.Duration

@Service
class MetricQueryService(
    private val metricRepository: MetricRepository,
) {

    /**
     * Мгновенный запрос: возвращает значение на указанный момент времени.
     * @param query PromQL выражение
     * @param time момент времени (если null, используется текущее время)
     */
    fun queryInstant(query: String, time: Instant? = null): Any {
        val evalTime = time ?: Instant.now()
        val metricRefs = ExpressionParser.extractMetricReferences(query)
        val seriesMap = mutableMapOf<String, List<Double>>()

        for (ref in metricRefs) {
            val key = ref.toKey()
            if (!seriesMap.containsKey(key)) {
                val values = loadMetricValuesInstant(ref, evalTime)
                seriesMap[key] = values
            }
        }

        // Для instant запроса из списка берём последнее значение
        return when (val result = ExpressionParser.parse(query, seriesMap)) {
            is List<*> -> result.takeIf { it.isNotEmpty() }?.last()
            else -> result
        } ?: Double.NaN
    }

    /**
     * Диапазонный запрос: возвращает матрицу значений на каждом шаге.
     * @param query PromQL выражение
     * @param start начальное время
     * @param end конечное время
     * @param step шаг (например, "15s")
     */
    fun queryRange(query: String, start: Instant, end: Instant, step: Duration): List<RangeQueryResult> {
        val steps = generateSequence(start) { it.plus(step) }
            .takeWhile { it <= end }
            .toList()

        // Для каждого шага выполняем instant запрос
        return steps.map { stepTime ->
            val value = queryInstant(query, stepTime)
            RangeQueryResult(timestamp = stepTime, value = value)
        }
    }

    private fun loadMetricValuesInstant(ref: MetricReference, time: Instant): List<Double> {
        val metrics = when {
            ref.tags.isEmpty() -> {
                metricRepository.findByNameBefore(ref.name, time)
            }

            ref.tags.size == 1 -> {
                val (key, value) = ref.tags.entries.first()
                metricRepository.findByNameAndTagBefore(ref.name, key, value, time)
            }

            ref.tags.size == 2 -> {
                val entries = ref.tags.entries.toList()
                metricRepository.findByNameAndTwoTagsBefore(
                    ref.name,
                    entries[0].key, entries[0].value,
                    entries[1].key, entries[1].value,
                    time
                )
            }

            else -> {
                // Больше двух тегов - фильтруем в памяти
                val allMetrics = metricRepository.findByNameBefore(ref.name, time)
                allMetrics.filter { metric ->
                    val metricTags = metric.metricTags.associate { it.tagDict.tagKey to it.tagDict.tagValue }
                    ref.tags.all { (k, v) -> metricTags[k] == v }
                }
            }
        }
        return metrics.sortedBy { it.timestamp }.map { it.value }
    }

    // Вспомогательный класс для результата range запроса
    data class RangeQueryResult(val timestamp: Instant, val value: Any)

//    private fun loadMetricValues(ref: MetricReference): List<Double> {
//        val metrics = if (ref.tags.isEmpty()) {
//            // Без тегов
//            if (ref.rangeSeconds != null) {
//                val cutoff = Instant.now().minusSeconds(ref.rangeSeconds)
//                metricRepository.findByNameAfter(ref.name, cutoff)
//            } else {
//                metricRepository.findByName(ref.name)
//            }
//        } else if (ref.tags.size == 1) {
//            // Один тег
//            val (key, value) = ref.tags.entries.first()
//            if (ref.rangeSeconds != null) {
//                val cutoff = Instant.now().minusSeconds(ref.rangeSeconds)
//                metricRepository.findByNameAndTagAfter(ref.name, key, value, cutoff)
//            } else {
//                metricRepository.findByNameAndTag(ref.name, key, value)
//            }
//        } else if (ref.tags.size == 2) {
//            // Два тега
//            val entries = ref.tags.entries.toList()
//            metricRepository.findByNameAndTwoTags(
//                ref.name,
//                entries[0].key, entries[0].value,
//                entries[1].key, entries[1].value
//            )
//        } else {
//            // Больше двух тегов - фильтруем в памяти
//            val allMetrics = if (ref.rangeSeconds != null) {
//                val cutoff = Instant.now().minusSeconds(ref.rangeSeconds)
//                metricRepository.findByNameAfter(ref.name, cutoff)
//            } else {
//                metricRepository.findByName(ref.name)
//            }
//
//            allMetrics.filter { metric ->
//                val metricTags = metric.metricTags.associate {
//                    it.tagDict.tagKey to it.tagDict.tagValue
//                }
//                ref.tags.all { (key, value) -> metricTags[key] == value }
//            }
//        }
//
//        return metrics.sortedBy { it.timestamp }.map { it.value }
//    }
}