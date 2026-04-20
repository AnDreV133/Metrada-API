package com.metrada.service

import com.metrada.model.MetricReference
import com.metrada.repository.MetricRepository
import com.metrada.util.math.ExpressionParser
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class MetricQueryService(
    private val metricRepository: MetricRepository,
) {

    /**
     * Выполнить PromQL запрос
     *
     * Примеры:
     * - "sum(node_cpu_seconds_total{cpu="0"}, node_cpu_seconds_total{cpu="1"})"
     * - "rate(node_cpu_seconds_total{mode="system"}[5m], 15, 60)"
     * - "avg_over_time(node_memory_usage_bytes[1h], 15, 3600)"
     * - "clamp_min(multiply_scalar(subtract(vector(1), avg(rate(node_cpu_seconds_total{mode="idle"}[5m], 15, 60))), 100), 0)"
     */
    fun execute(query: String): Any {
        // 1. Извлекаем все метрики из запроса
        val metricRefs = ExpressionParser.extractMetricReferences(query)

        // 2. Загружаем данные для каждой метрики
        val seriesMap = mutableMapOf<String, List<Double>>()

        for (ref in metricRefs) {
            val key = ref.toKey()
            if (!seriesMap.containsKey(key)) {
                val values = loadMetricValues(ref)
                seriesMap[key] = values
            }
        }

        // 3. Выполняем запрос
        return ExpressionParser.parse(query, seriesMap)
    }

    private fun loadMetricValues(ref: MetricReference): List<Double> {
        val metrics = if (ref.tags.isEmpty()) {
            // Без тегов
            if (ref.rangeSeconds != null) {
                val cutoff = Instant.now().minusSeconds(ref.rangeSeconds)
                metricRepository.findByNameAfter(ref.name, cutoff)
            } else {
                metricRepository.findByName(ref.name)
            }
        } else if (ref.tags.size == 1) {
            // Один тег
            val (key, value) = ref.tags.entries.first()
            if (ref.rangeSeconds != null) {
                val cutoff = Instant.now().minusSeconds(ref.rangeSeconds)
                metricRepository.findByNameAndTagAfter(ref.name, key, value, cutoff)
            } else {
                metricRepository.findByNameAndTag(ref.name, key, value)
            }
        } else if (ref.tags.size == 2) {
            // Два тега
            val entries = ref.tags.entries.toList()
            metricRepository.findByNameAndTwoTags(
                ref.name,
                entries[0].key, entries[0].value,
                entries[1].key, entries[1].value
            )
        } else {
            // Больше двух тегов - фильтруем в памяти
            val allMetrics = if (ref.rangeSeconds != null) {
                val cutoff = Instant.now().minusSeconds(ref.rangeSeconds)
                metricRepository.findByNameAfter(ref.name, cutoff)
            } else {
                metricRepository.findByName(ref.name)
            }

            allMetrics.filter { metric ->
                val metricTags = metric.metricTags.associate {
                    it.tagDict.tagKey to it.tagDict.tagValue
                }
                ref.tags.all { (key, value) -> metricTags[key] == value }
            }
        }

        return metrics.sortedBy { it.timestamp }.map { it.value }
    }
}