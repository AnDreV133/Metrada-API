package com.metrada.repository

import com.metrada.entity.MetricEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface MetricRepository : JpaRepository<MetricEntity, Long> {

    /**
     * Поиск метрик по agent_id и временному диапазону
     */
    @Query(
        """
        SELECT m FROM MetricEntity m
        WHERE m.agentId = :agentId
          AND m.timestamp BETWEEN :start AND :end
        ORDER BY m.timestamp DESC
    """
    )
    fun findByAgentIdAndTimeRange(
        @Param("agentId") agentId: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<MetricEntity>

    /**
     * Поиск метрик по имени и временному диапазону
     */
    @Query(
        """
        SELECT m FROM MetricEntity m
        WHERE m.name = :name
          AND m.timestamp BETWEEN :start AND :end
        ORDER BY m.timestamp DESC
    """
    )
    fun findByNameAndTimeRange(
        @Param("name") name: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<MetricEntity>

    /**
     * Поиск метрик по имени и конкретному тегу
     */
    @Query(
        """
        SELECT DISTINCT m FROM MetricEntity m
        JOIN m.metricTags mt
        JOIN mt.tagDict td
        WHERE m.name = :metricName
          AND td.tagKey = :tagKey
          AND td.tagValue = :tagValue
          AND m.timestamp BETWEEN :start AND :end
        ORDER BY m.timestamp DESC
    """
    )
    fun findByNameAndTag(
        @Param("metricName") metricName: String,
        @Param("tagKey") tagKey: String,
        @Param("tagValue") tagValue: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<MetricEntity>

    /**
     * Поиск метрик по имени и нескольким тегам (AND)
     */
    @Query(
        """
        SELECT m FROM MetricEntity m
        WHERE m.name = :metricName
          AND m.timestamp BETWEEN :start AND :end
          AND EXISTS (
              SELECT 1 FROM MetricTagEntity mt1 
              JOIN mt1.tagDict td1
              WHERE mt1.metric = m 
                AND td1.tagKey = :key1 
                AND td1.tagValue = :value1
          )
          AND EXISTS (
              SELECT 1 FROM MetricTagEntity mt2 
              JOIN mt2.tagDict td2
              WHERE mt2.metric = m 
                AND td2.tagKey = :key2 
                AND td2.tagValue = :value2
          )
    """
    )
    fun findByNameAndTwoTags(
        @Param("metricName") metricName: String,
        @Param("key1") key1: String,
        @Param("value1") value1: String,
        @Param("key2") key2: String,
        @Param("value2") value2: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<MetricEntity>

    /**
     * Поиск метрик по наличию тега (независимо от значения)
     */
    @Query(
        """
        SELECT DISTINCT m FROM MetricEntity m
        JOIN m.metricTags mt
        JOIN mt.tagDict td
        WHERE td.tagKey = :tagKey
          AND m.timestamp BETWEEN :start AND :end
    """
    )
    fun findByTagKey(
        @Param("tagKey") tagKey: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<MetricEntity>

    /**
     * Поиск метрик по множеству агентов
     */
    @Query(
        """
        SELECT m FROM MetricEntity m
        WHERE m.agentId IN :agentIds
          AND m.timestamp BETWEEN :start AND :end
        ORDER BY m.timestamp DESC
    """
    )
    fun findByAgentIds(
        @Param("agentIds") agentIds: Set<String>,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<MetricEntity>

    /**
     * Получение последней метрики для агента
     */
    @Query(
        """
        SELECT m FROM MetricEntity m
        WHERE m.agentId = :agentId
        ORDER BY m.timestamp DESC
        LIMIT 1
    """
    )
    fun findLastMetricByAgentId(@Param("agentId") agentId: String): MetricEntity?

    /**
     * Агрегация: среднее значение по времени (для TimescaleDB)
     */
    @Query(
        value = """
        SELECT time_bucket(:bucket, timestamp) as bucket,
               AVG(value) as avg_value,
               MAX(value) as max_value,
               MIN(value) as min_value,
               COUNT(*) as count
        FROM metrics 
        WHERE name = :metricName
          AND timestamp BETWEEN :start AND :end
        GROUP BY bucket
        ORDER BY bucket DESC
    """, nativeQuery = true
    )
    fun getTimeAggregation(
        @Param("metricName") metricName: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
        @Param("bucket") bucket: String = "1 minute",
    ): List<Array<Any>>

    /**
     * Агрегация с группировкой по тегам
     */
    @Query(
        value = """
        SELECT td.tag_value, 
               AVG(m.value) as avg_value,
               COUNT(m) as metric_count
        FROM metrics m
        JOIN metric_tags mt ON m.id = mt.metric_id
        JOIN tags_dict td ON mt.tag_dict_id = td.id
        WHERE m.name = :metricName
          AND td.tag_key = :groupByKey
          AND m.timestamp BETWEEN :start AND :end
        GROUP BY td.tag_value
    """, nativeQuery = true
    )
    fun aggregateByTag(
        @Param("metricName") metricName: String,
        @Param("groupByKey") groupByKey: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<Array<Any>>

    /**
     * Статистика по агенту
     */
    @Query(
        """
        SELECT COUNT(m), AVG(m.value), MIN(m.timestamp), MAX(m.timestamp)
        FROM MetricEntity m
        WHERE m.agentId = :agentId
          AND m.timestamp BETWEEN :start AND :end
    """
    )
    fun getAgentStats(
        @Param("agentId") agentId: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): Array<Any>

    /**
     * Удаление старых метрик (для политик ретеншна)
     */
    @Modifying
    @Query("DELETE FROM MetricEntity m WHERE m.timestamp < :threshold")
    fun deleteOlderThan(@Param("threshold") threshold: Instant)
}