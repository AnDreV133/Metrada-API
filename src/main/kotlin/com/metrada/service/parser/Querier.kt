package com.metrada.service.parser

import com.metrada.entity.TagDictEntity
import com.metrada.repository.MetricRepository
import com.metrada.repository.MetricTagRepository
import com.metrada.repository.TagDictRepository
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class Querier(
    private val metricRepository: MetricRepository,
    private val metricTagRepository: MetricTagRepository,
    private val tagDictRepository: TagDictRepository,
) {

    suspend fun select(mint: Long, maxt: Long, matchers: List<LabelMatcher>): ISeriesSet {
        // 1. Разбираем matchers на имя метрики, agentId, и теги
        var metricName: String? = null
        var agentId: String? = null
        val tagConditions = mutableMapOf<String, String>() // key -> value

        for (m in matchers) {
            // Поддерживаем только точное равенство (MatchType.Equal) для простоты
            if (m.type != MatchTypeEnum.Equal) continue
            when (m.name) {
                "__name__" -> metricName = m.value
                "instance" -> agentId = m.value
                else -> tagConditions[m.name] = m.value
            }
        }

        if (metricName == null) error("metric name (__name__) is required")
        if (agentId == null) error("agent label is required")

        // 2. Находим ID серий (metrics), удовлетворяющих тегам
        val tagHashes = tagConditions.map { (k, v) ->
            TagDictEntity.generateHash(k, v)
        }
        val metricIds = if (tagHashes.isNotEmpty()) {
            metricTagRepository.findMetricIdsByTagHashes(tagHashes, tagHashes.size.toLong())
        } else {
            // Если нет тегов – все метрики с таким именем и агентом
            metricRepository.findByNameAndAgentAndTimeRange(
                metricName,
                agentId,
                Instant.ofEpochMilli(mint),
                Instant.ofEpochMilli(maxt)
            )
                .map { it.id!! }.distinct()
        }

        // 3. Загружаем все точки для отобранных metricId в диапазоне времени
        val entities = metricRepository.findAllById(metricIds)
            .filter {
                it.name == metricName
                        && it.agentId == agentId
                        && it.timestamp in Instant.ofEpochMilli(mint)..Instant.ofEpochMilli(maxt)
            }
            .sortedBy { it.timestamp }

        // 4. Группируем по метрике + тегам (формируем Labels)
        val seriesMap = mutableMapOf<Labels, MutableList<FPoint>>()
        for (e in entities) {
            // Загружаем теги этой метрики (можно лениво, здесь упрощённо)
            val tags = e.metricTags.associate { it.tagDict.tagKey to it.tagDict.tagValue }
            val labels = Labels(tags + ("__name__" to e.name) + ("instance" to e.agentId))
            val point = FPoint(e.timestamp.toEpochMilli(), e.value)
            seriesMap.getOrPut(labels) { mutableListOf() }.add(point)
        }

        // 5. Сортируем точки внутри каждой серии по времени
        for (points in seriesMap.values) {
            points.sortBy { it.t }
        }

        val seriesList = seriesMap.map { (labels, points) ->
            object : IStorageSeries {
                override fun labels() = labels
                override fun iterator() = object : ISeriesIterator {
                    private var idx = 0
                    override fun seek(ts: Long): Boolean {
                        idx = points.binarySearch { it.t.compareTo(ts) }.let { if (it < 0) -it - 1 else it }
                        return idx < points.size
                    }

                    override fun next(): Boolean = (++idx) < points.size
                    override fun at(): FPoint = points[idx]
                    override fun error(): Throwable? = null
                }
            }
        }

        return PostgresSeriesSet(seriesList)
    }

    private inner class PostgresSeriesSet(private val series: List<IStorageSeries>) : ISeriesSet {
        private var pos = -1
        override suspend fun next(): Boolean {
            if (pos + 1 < series.size) {
                pos++
                return true
            }
            return false
        }

        override fun at() = series[pos]
        override fun warnings() = listOf<Throwable>()
        override fun error() = null
    }
}