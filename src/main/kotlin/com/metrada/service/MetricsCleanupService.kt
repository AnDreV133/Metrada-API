package com.metrada.service

import com.metrada.entity.AgentEntity
import com.metrada.repository.AgentRepository
import com.metrada.repository.MetricRepository
import com.metrada.repository.MetricTagRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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

    // Настройки очистки
    private val cleanupIntervalHours = 6L  // Запуск каждые 6 часов
    private val batchSize = 1000  // Размер пакета для удаления

    @PostConstruct
    fun start() {
        logger.info("Starting metrics cleanup service")

        cleanupJob = scope.launch {
            while (isActive) {
                try {
                    performCleanup()
                } catch (e: Exception) {
                    logger.error("Cleanup failed: ${e.message}", e)
                }

                // Ждём следующий цикл
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
            // Получаем всех агентов с их настройками retention
            val agents = agentRepository.findAll()

            var totalDeleted = 0L
            val cleanupResults = mutableListOf<CleanupResult>()

            // Очищаем метрики для каждого агента
            agents.forEach { agent ->
                val deleted = cleanupMetricsForAgent(agent)
                if (deleted > 0) {
                    cleanupResults.add(
                        CleanupResult(
                            agentId = agent.id,
                            retentionDays = agent.retentionDays,
                            deletedCount = deleted
                        )
                    )
                    totalDeleted += deleted
                }
            }

            // Очищаем orphaned теги (теги, которые не используются)
            val orphanedTagsDeleted = cleanupOrphanedTags()

            val duration = java.time.Duration.between(startTime, Instant.now())
            logger.info(
                "Cleanup completed: deleted $totalDeleted metrics, $orphanedTagsDeleted orphaned tags in ${duration.seconds}s"
            )

            // Логируем детали
            if (cleanupResults.isNotEmpty()) {
                logger.debug("Cleanup details: {}", cleanupResults)
            }

        } catch (e: Exception) {
            logger.error("Error during cleanup cycle: ${e.message}", e)
        }
    }

    /**
     * Очистка метрик для конкретного агента
     */
    private suspend fun cleanupMetricsForAgent(agent: AgentEntity): Long {
        val retentionThreshold = agent.getRetentionThreshold()

        logger.debug(
            "Cleaning metrics for agent ${agent.id}: " +
                    "retention=${agent.retentionDays} days, " +
                    "threshold=$retentionThreshold"
        )

        var totalDeleted = 0L

        try {
            // Удаляем пакетами, чтобы не перегружать БД
            while (true) {
                val deleted = metricRepository.deleteOldMetricsForAgent(
                    agentId = agent.id,
                    olderThan = retentionThreshold,
                    limit = batchSize
                )

                if (deleted == 0) break
                totalDeleted += deleted

                logger.debug("Deleted {} metrics for agent {} (total: {})", deleted, agent.id, totalDeleted)

                // Небольшая задержка между пакетами
                delay(100)
            }
        } catch (e: Exception) {
            logger.error("Error cleaning metrics for agent ${agent.id}: ${e.message}", e)
        }

        return totalDeleted
    }

    /**
     * Очистка orphaned тегов (теги, которые не связаны ни с одной метрикой)
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
     * Принудительный запуск очистки (можно вызвать через API)
     */
    suspend fun forceCleanup(): CleanupSummary {
        logger.info("Forced cleanup requested")
        val startTime = Instant.now()

        performCleanup()

        val duration = java.time.Duration.between(startTime, Instant.now())

        return CleanupSummary(
            timestamp = Instant.now(),
            durationSeconds = duration.seconds,
            status = "completed"
        )
    }

    /**
     * Очистка метрик для конкретного агента по запросу
     */
    suspend fun cleanupForAgent(agentId: String): CleanupResult? {
        val agent = agentRepository.findById(agentId).orElse(null)
            ?: return null

        logger.info("Forced cleanup for agent $agentId")
        val deletedCount = cleanupMetricsForAgent(agent)

        return CleanupResult(
            agentId = agentId,
            retentionDays = agent.retentionDays,
            deletedCount = deletedCount
        )
    }
}

/**
 * Результат очистки для агента
 */
data class CleanupResult(
    val agentId: String,
    val retentionDays: Int,
    val deletedCount: Long,
)

/**
 * Сводка очистки
 */
data class CleanupSummary(
    val timestamp: Instant,
    val durationSeconds: Long,
    val status: String,
)