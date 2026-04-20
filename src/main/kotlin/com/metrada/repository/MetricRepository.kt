package com.metrada.repository

import com.metrada.entity.MetricEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
interface MetricRepository : JpaRepository<MetricEntity, Long> {

    // ==================== Базовые методы поиска ====================

    /**
     * Поиск всех метрик по имени (без ограничения по времени)
     */
    @Query("SELECT m FROM MetricEntity m WHERE m.name = :name ORDER BY m.timestamp ASC")
    fun findByName(@Param("name") name: String): List<MetricEntity>

    /**
     * Поиск метрик по имени после указанного времени
     */
    @Query("SELECT m FROM MetricEntity m WHERE m.name = :name AND m.timestamp >= :after ORDER BY m.timestamp ASC")
    fun findByNameAfter(@Param("name") name: String, @Param("after") after: Instant): List<MetricEntity>

    /**
     * Поиск метрик по имени и временному диапазону
     */
    @Query("SELECT m FROM MetricEntity m WHERE m.name = :name AND m.timestamp BETWEEN :start AND :end ORDER BY m.timestamp ASC")
    fun findByNameAndTimeRange(
        @Param("name") name: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<MetricEntity>

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

    // ==================== Поиск по тегам ====================

    /**
     * Поиск метрик по имени и тегу (без ограничения по времени)
     */
    @Query(
        """
        SELECT DISTINCT m FROM MetricEntity m
        JOIN m.metricTags mt
        JOIN mt.tagDict td
        WHERE m.name = :name
          AND td.tagKey = :tagKey
          AND td.tagValue = :tagValue
        ORDER BY m.timestamp ASC
        """
    )
    fun findByNameAndTag(
        @Param("name") name: String,
        @Param("tagKey") tagKey: String,
        @Param("tagValue") tagValue: String,
    ): List<MetricEntity>

    /**
     * Поиск метрик по имени и тегу после указанного времени
     */
    @Query(
        """
        SELECT DISTINCT m FROM MetricEntity m
        JOIN m.metricTags mt
        JOIN mt.tagDict td
        WHERE m.name = :name
          AND td.tagKey = :tagKey
          AND td.tagValue = :tagValue
          AND m.timestamp >= :after
        ORDER BY m.timestamp ASC
        """
    )
    fun findByNameAndTagAfter(
        @Param("name") name: String,
        @Param("tagKey") tagKey: String,
        @Param("tagValue") tagValue: String,
        @Param("after") after: Instant,
    ): List<MetricEntity>

    /**
     * Поиск метрик по имени и тегу с временным диапазоном
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
    fun findByNameAndTagWithTimeRange(
        @Param("metricName") metricName: String,
        @Param("tagKey") tagKey: String,
        @Param("tagValue") tagValue: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<MetricEntity>

    /**
     * Поиск метрик по имени и нескольким тегам (AND) - без временного диапазона
     */
    @Query(
        """
        SELECT m FROM MetricEntity m
        WHERE m.name = :name
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
        ORDER BY m.timestamp ASC
        """
    )
    fun findByNameAndTwoTags(
        @Param("name") name: String,
        @Param("key1") key1: String,
        @Param("value1") value1: String,
        @Param("key2") key2: String,
        @Param("value2") value2: String,
    ): List<MetricEntity>

    /**
     * Поиск метрик по имени и нескольким тегам с временным диапазоном
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
        ORDER BY m.timestamp DESC
        """
    )
    fun findByNameAndTwoTagsWithTimeRange(
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

    // ==================== Методы для последних метрик ====================

    /**
     * Получение последней метрики по имени
     */
    @Query("SELECT m FROM MetricEntity m WHERE m.name = :name ORDER BY m.timestamp DESC LIMIT 1")
    fun findLastByName(@Param("name") name: String): MetricEntity?

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
     * Получение временного диапазона для метрики
     */
    @Query("SELECT MIN(m.timestamp), MAX(m.timestamp) FROM MetricEntity m WHERE m.name = :name")
    fun getTimeRange(@Param("name") name: String): Array<Any?>

    // ==================== Агрегационные методы ====================

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
        """,
        nativeQuery = true
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
        """,
        nativeQuery = true
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

    // ==================== Методы удаления (ретентеншн) ====================

    /**
     * Удаление старых метрик для конкретного агента с лимитом (PostgreSQL compatible)
     * Использует CTE для LIMIT в DELETE
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            WITH to_delete AS (
                SELECT id FROM metrics 
                WHERE agent_id = :agentId 
                  AND timestamp < :olderThan
                LIMIT :limit
            )
            DELETE FROM metrics 
            WHERE id IN (SELECT id FROM to_delete)
        """,
        nativeQuery = true
    )
    fun deleteOldMetricsForAgent(
        @Param("agentId") agentId: String,
        @Param("olderThan") olderThan: Instant,
        @Param("limit") limit: Int,
    ): Int

    /**
     * Удаление старых метрик для всех агентов с лимитом (PostgreSQL compatible)
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            WITH to_delete AS (
                SELECT id FROM metrics 
                WHERE timestamp < :olderThan
                LIMIT :limit
            )
            DELETE FROM metrics 
            WHERE id IN (SELECT id FROM to_delete)
        """,
        nativeQuery = true
    )
    fun deleteOldMetrics(
        @Param("olderThan") olderThan: Instant,
        @Param("limit") limit: Int,
    ): Int

    /**
     * Удаление всех старых метрик для агента (без лимита)
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            DELETE FROM metrics 
            WHERE agent_id = :agentId 
              AND timestamp < :olderThan
        """,
        nativeQuery = true
    )
    fun deleteAllOldMetricsForAgent(
        @Param("agentId") agentId: String,
        @Param("olderThan") olderThan: Instant,
    ): Int

    /**
     * Удаление старых метрик (для политик ретеншна) - без лимита
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM MetricEntity m WHERE m.timestamp < :threshold")
    fun deleteOlderThan(@Param("threshold") threshold: Instant): Int

    /**
     * Получить количество метрик для агента старше указанной даты
     */
    @Query("SELECT COUNT(m) FROM MetricEntity m WHERE m.agentId = :agentId AND m.timestamp < :olderThan")
    fun countOldMetricsForAgent(
        @Param("agentId") agentId: String,
        @Param("olderThan") olderThan: Instant,
    ): Long
}