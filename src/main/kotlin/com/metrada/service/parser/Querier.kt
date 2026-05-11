package com.metrada.service.parser

import com.github.benmanes.caffeine.cache.Caffeine
import com.metrada.entity.LabelDictEntity
import com.metrada.repository.MetricRepository
import com.metrada.repository.MetricLabelRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.TimeUnit

@Component
class Querier(
    private val metricRepository: MetricRepository,
    private val metricLabelRepository: MetricLabelRepository
) {
    private val cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<CacheKey, List<Int>>()

    suspend fun select(mint: Long, maxt: Long, matchers: List<LabelMatcher>): ISeriesSet {
        val (required, regex, negate, negateRegex) = categorizeMatchers(matchers)
        if (required.isEmpty()) return EmptySeriesSet

        val metricHashes = cache.get(CacheKey(mint, maxt, required)) {
            val requiredHashes = required.map { (k, v) -> LabelDictEntity.generateHash(k, v) }
            metricLabelRepository.findMetricHashesByRequiredLabelHashes(requiredHashes, requiredHashes.size)
        }
        if (metricHashes.isEmpty()) return EmptySeriesSet

        val start = Instant.ofEpochMilli(mint)
        val end = Instant.ofEpochMilli(maxt)
        val entities = metricRepository.findAllByHashesAndTimeRange(metricHashes, start, end)
        if (entities.isEmpty()) return EmptySeriesSet

        val filtered = entities.filter { metric ->
            val labels = metric.metricLabels.associate { it.labelDict.labelKey to it.labelDict.labelValue }
            regex.all { (k, r) -> r.matches(labels[k] ?: "") } &&
                    negate.all { (k, v) -> labels[k] != v } &&
                    negateRegex.all { (k, r) -> !r.matches(labels[k] ?: "") }
        }
        if (filtered.isEmpty()) return EmptySeriesSet

        val grouped = filtered.sortedBy { it.timestamp }
            .groupBy { Labels(it.metricLabels.associate { it.labelDict.labelKey to it.labelDict.labelValue }) }
            .mapValues { (_, metrics) -> metrics.map { FPoint(it.timestamp.toEpochMilli(), it.value) } }

        val seriesList = grouped.map { (labels, points) ->
            object : IStorageSeries {
                override fun labels() = labels
                override fun iterator() = object : ISeriesIterator {
                    private var idx = 0
                    override fun seek(ts: Long): Boolean {
                        idx = points.binarySearchBy(ts) { it.timestamp }.let { if (it < 0) -it - 1 else it }
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

    private fun categorizeMatchers(matchers: List<LabelMatcher>): LabelCategories {
        val required = mutableMapOf<String, String>()
        val regex = mutableListOf<Pair<String, Regex>>()
        val negate = mutableListOf<Pair<String, String>>()
        val negateRegex = mutableListOf<Pair<String, Regex>>()
        for (m in matchers) {
            when (m.type) {
                MatchTypeEnum.Equal -> required[m.name] = m.value
                MatchTypeEnum.Regexp -> regex.add(m.name to m.value.toRegex())
                MatchTypeEnum.NotEqual -> negate.add(m.name to m.value)
                MatchTypeEnum.NotRegexp -> negateRegex.add(m.name to m.value.toRegex())
            }
        }
        return LabelCategories(required, regex, negate, negateRegex)
    }

    private data class CacheKey(
        val mint: Long,
        val maxt: Long,
        val required: Map<String, String>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CacheKey
            return mint == other.mint && maxt == other.maxt && required == other.required
        }
        override fun hashCode(): Int = 31 * (31 * mint.hashCode() + maxt.hashCode()) + required.hashCode()
    }

    private data class LabelCategories(
        val required: Map<String, String>,
        val regex: List<Pair<String, Regex>>,
        val negate: List<Pair<String, String>>,
        val negateRegex: List<Pair<String, Regex>>
    )

    private object EmptySeriesSet : ISeriesSet {
        override suspend fun next() = false
        override fun at() = error("No series")
        override fun warnings() = emptyList<Throwable>()
        override fun error() = null
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
        override fun warnings() = emptyList<Throwable>()
        override fun error() = null
    }
}

private fun <T> List<T>.binarySearchBy(key: Long, selector: (T) -> Long): Int {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midVal = selector(this[mid])
        when {
            midVal < key -> low = mid + 1
            midVal > key -> high = mid - 1
            else -> return mid
        }
    }
    return -low - 1
}