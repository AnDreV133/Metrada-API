package com.metrada.service

import com.metrada.repository.AgentRepository
import com.metrada.repository.MetricRepository
import com.metrada.repository.MetricTagRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.hours

@Service
class MetricsCleanupService(
    private val metricRepository: MetricRepository,
    private val metricTagRepository: MetricTagRepository,
    private val agentRepository: AgentRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cleanupJob: Job? = null

    private val cleanupIntervalHours = 6L
    private val batchSize = 1000

    @PostConstruct
    fun start() {
        logger.info("Starting metrics cleanup service with JPA cascade")

        cleanupJob = scope.launch {
            while (isActive) {
                try {
                    performCleanup()
                } catch (e: Exception) {
                    logger.error("Cleanup failed: ${e.message}", e)
                }
                delay(cleanupIntervalHours.hours.inWholeMilliseconds)
            }
        }
    }

    @PreDestroy
    fun stop() {
        logger.info("Stopping metrics cleanup service")
        cleanupJob?.cancel()
        scope.cancel()
    }

    /**
     * Основной метод очистки старых метрик
     */
    private suspend fun performCleanup() {
        logger.info("Starting metrics cleanup cycle")
        val startTime = Instant.now()

        try {
            val agents = agentRepository.findAll()
            var totalDeleted = 0L
            val cleanupResults = mutableListOf<CleanupResult>()

            for (agent in agents) {
                val retentionThreshold = agent.getRetentionThreshold()
                val deleted = cleanupMetricsForAgent(agent.id, retentionThreshold)

                if (deleted > 0) {
                    cleanupResults.add(
                        CleanupResult(
                            agentId = agent.id,
                            retentionDays = agent.retentionDays,
                            deletedCount = deleted
                        )
                    )
                    totalDeleted += deleted
                    logger.info("Deleted $deleted metrics for agent ${agent.id}")
                }
            }

            // Очищаем orphaned теги (теги без связей)
            val orphanedTagsDeleted = cleanupOrphanedTags()

            val duration = Duration.between(startTime, Instant.now())
            logger.info(
                "Cleanup completed: deleted $totalDeleted metrics, $orphanedTagsDeleted orphaned tags in ${duration.seconds}s"
            )

        } catch (e: Exception) {
            logger.error("Error during cleanup cycle: ${e.message}", e)
        }
    }

    /**
     * Очистка метрик для конкретного агента с использованием JPA
     * Благодаря JPA cascade, связи в metric_tags удаляются автоматически
     */
    private suspend fun cleanupMetricsForAgent(agentId: String, olderThan: Instant): Long {
        var totalDeleted = 0L

        try {
            while (true) {
                // Используем JPA метод - каскад сработает автоматически
                val deleted = deleteOldMetricsForAgent(
                    agentId = agentId,
                    olderThan = olderThan,
                    limit = batchSize
                )

                if (deleted == 0) break
                totalDeleted += deleted

                logger.debug("Deleted batch of $deleted metrics for agent $agentId (total: $totalDeleted)")
                delay(100)
            }
        } catch (e: Exception) {
            logger.error("Error cleaning metrics for agent $agentId: ${e.message}", e)
        }

        return totalDeleted
    }

    @Transactional
    fun deleteOldMetricsForAgent(agentId: String, olderThan: Instant, limit: Int): Int {
        // Находим ID метрик для удаления
        val ids = metricRepository.findOldMetricIdsForAgent(agentId, olderThan, limit)
        if (ids.isEmpty()) return 0

        // Удаляем через JPA - каскад сработает автоматически
        metricRepository.deleteAllByIdIn(ids)
        return ids.size // todo too many ids, cant delete metrics, need batching
    }

    /**
     * Очистка orphaned тегов
     * Теги, которые не связаны ни с одной метрикой, можно удалить
     */
    private suspend fun cleanupOrphanedTags(): Long {
        logger.debug("Cleaning orphaned tags")
        var totalDeleted = 0L

        try {
            while (true) {
                val deleted = metricTagRepository.deleteOrphanedTags(batchSize)
                if (deleted == 0) break
                totalDeleted += deleted
                logger.debug("Deleted $deleted orphaned tags (total: $totalDeleted)")
                delay(100)
            }
        } catch (e: Exception) {
            logger.error("Error cleaning orphaned tags: ${e.message}", e)
        }

        return totalDeleted
    }

    /**
     * Принудительный запуск очистки для всех агентов
     */
    suspend fun forceCleanup(): CleanupSummary {
        logger.info("Forced cleanup requested")
        val startTime = Instant.now()

        performCleanup()

        val duration = Duration.between(startTime, Instant.now())

        return CleanupSummary(
            timestamp = Instant.now(),
            durationSeconds = duration.seconds,
            status = "completed"
        )
    }

    /**
     * Очистка для конкретного агента по запросу
     */
    suspend fun cleanupForAgent(agentId: String): CleanupResult? {
        val agent = agentRepository.findById(agentId).orElse(null) ?: return null

        logger.info("Forced cleanup for agent $agentId")
        val retentionThreshold = agent.getRetentionThreshold()
        val deletedCount = cleanupMetricsForAgent(agentId, retentionThreshold)

        return CleanupResult(
            agentId = agentId,
            retentionDays = agent.retentionDays,
            deletedCount = deletedCount
        )
    }
}

data class CleanupResult(
    val agentId: String,
    val retentionDays: Int,
    val deletedCount: Long,
)

data class CleanupSummary(
    val timestamp: Instant,
    val durationSeconds: Long,
    val status: String,
)