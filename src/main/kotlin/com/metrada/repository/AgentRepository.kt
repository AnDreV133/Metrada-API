package com.metrada.repository

import com.metrada.entity.AgentEntity
import com.metrada.entity.MetricEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface AgentRepository : JpaRepository<AgentEntity, String> {
    // Поиск метрик агента через прямой запрос (не через связь)
    @Query("SELECT m FROM MetricEntity m WHERE m.agentId = :agentId")
    fun findMetricsByAgentId(@Param("agentId") agentId: String): List<MetricEntity>

    /**
     * Найти всех активных агентов (enabled = true)
     */
    fun findByEnabledTrue(): List<AgentEntity>

    /**
     * Найти агентов для опроса (активные и с корректным интервалом)
     */
    @Query("SELECT a FROM AgentEntity a WHERE a.enabled = true AND a.scrapeIntervalSeconds > 0")
    fun findActiveAgents(): List<AgentEntity>

    /**
     * Обновить статус последнего скрапинга
     */
    @Modifying
    @Query(
        """
        UPDATE AgentEntity a 
        SET a.lastScrapeAt = :lastScrapeAt, 
            a.lastScrapeStatus = :status,
            a.updatedAt = :lastScrapeAt
        WHERE a.id = :agentId
    """
    )
    fun updateScrapeStatus(
        @Param("agentId") agentId: String,
        @Param("lastScrapeAt") lastScrapeAt: Instant,
        @Param("status") status: String,
    )

    /**
     * Поиск агентов по хосту (частичное совпадение)
     */
    @Query("SELECT a FROM AgentEntity a WHERE a.host LIKE %:hostPattern%")
    fun findByHostContaining(@Param("hostPattern") hostPattern: String): List<AgentEntity>

    /**
     * Получить агентов, которые давно не опрашивались
     */
    @Query(
        """
        SELECT a FROM AgentEntity a 
        WHERE a.enabled = true 
          AND (a.lastScrapeAt IS NULL OR a.lastScrapeAt < :threshold)
    """
    )
    fun findStaleAgents(@Param("threshold") threshold: Instant): List<AgentEntity>

    /**
     * Подсчет агентов по статусам
     */
    @Query("SELECT COUNT(a) FROM AgentEntity a WHERE a.enabled = true")
    fun countActive(): Long

    @Query("SELECT COUNT(a) FROM AgentEntity a WHERE a.enabled = false")
    fun countInactive(): Long

    @Query("SELECT COUNT(a) FROM AgentEntity a WHERE a.lastScrapeStatus = 'success'")
    fun countWithSuccessfulScrape(): Long

    @Query("SELECT COUNT(a) FROM AgentEntity a WHERE a.lastScrapeStatus LIKE 'failed%'")
    fun countWithFailedScrape(): Long
}



