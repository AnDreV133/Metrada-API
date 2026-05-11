package com.metrada.service.parser

import com.metrada.entity.LabelDictEntity
import com.metrada.repository.MetricRepository
import com.metrada.repository.MetricLabelRepository
import com.metrada.repository.LabelDictRepository
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class Querier(
    private val metricRepository: MetricRepository,
    private val metricLabelRepository: MetricLabelRepository,
    private val engine: Engine
    ) {

    suspend fun select(mint: Long, maxt: Long, matchers: List<LabelMatcher>): ISeriesSet {
        // 1. Разбираем matchers на обязательные лейблы и возможные условия (=, =~, !=, !~)
        val requiredLabels = mutableMapOf<String, String>()   // для точных равенств
        val regexLabels = mutableListOf<Pair<String, Regex>>() // для =~
        val negateLabels = mutableListOf<Pair<String, String>>() // для !=
        val negateRegex = mutableListOf<Pair<String, Regex>>() // для !~

        for (m in matchers) {
            when (m.type) {
                MatchTypeEnum.Equal -> requiredLabels[m.name] = m.value
                MatchTypeEnum.Regexp -> regexLabels.add(m.name to m.value.toRegex())
                MatchTypeEnum.NotEqual -> negateLabels.add(m.name to m.value)
                MatchTypeEnum.NotRegexp -> negateRegex.add(m.name to m.value.toRegex())
            }
        }

        // 2. Получаем хэши для обязательных лейблов (из LabelDictEntity)
        val requiredHashes = requiredLabels.map { (k, v) -> LabelDictEntity.generateHash(k, v) }

        val metricHashes = if (requiredHashes.isNotEmpty()) {
            metricLabelRepository.findMetricHashesByRequiredLabelHashes(requiredHashes, requiredHashes.size)
        } else { emptyList() }

        val entities = if (metricHashes.isNotEmpty()) {
            metricRepository.findAllByHashesAndTimeRange(metricHashes, Instant.ofEpochMilli(mint), Instant.ofEpochMilli(maxt))
        } else { emptyList() }

        // 5. Применяем дополнительные фильтры (=~, !=, !~) в памяти
        val filteredEntities = entities.filter { metric ->
            // Получаем все лейблы метрики (можно загрузить одним запросом для всех метрик, но здесь упрощённо)
            val labels = metric.metricLabels.associate { it.labelDict.labelKey to it.labelDict.labelValue }
            // Должны выполняться все условия
            regexLabels.all { (key, regex) -> regex.matches(labels[key] ?: "") } &&
                    negateLabels.all { (key, value) -> labels[key] != value } &&
                    negateRegex.all { (key, regex) -> !regex.matches(labels[key] ?: "") }
        }

        // 6. Группировка в серии по всем лейблам
        val seriesMap = mutableMapOf<Labels, MutableList<FPoint>>()
        for (e in filteredEntities.sortedBy { it.timestamp }) {
            val labelsMap = e.metricLabels.associate { it.labelDict.labelKey to it.labelDict.labelValue }
            val point = FPoint(e.timestamp.toEpochMilli(), e.value)
            seriesMap.getOrPut(Labels(labelsMap)) { mutableListOf() }.add(point)
        }

        // 7. Сортировка и преобразование в итераторы (аналогично предыдущей версии)
        val seriesList = seriesMap.map { (hashes, points) ->
            object : IStorageSeries {
                override fun labels() = hashes
                override fun iterator() = object : ISeriesIterator {
                    private var idx = 0
                    override fun seek(ts: Long): Boolean {
                        idx = points.binarySearch { it.timestamp.compareTo(ts) }.let { if (it < 0) -it - 1 else it }
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