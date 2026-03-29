import com.metrada.service.AgentConfigurationService
import com.metrada.service.MetricsDynamicScrapingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/v1/scrapers")
class ScraperController(
    private val scrapingService: MetricsDynamicScrapingService,
    private val agentConfigurationService: AgentConfigurationService,
) {
    /**
     * REFRESH: Перезапустить ВСЕ воркеры
     * Полезно после изменения конфигурации или для сброса состояния
     */
    @PostMapping("/refresh")
    suspend fun refreshAllWorkers(): ResponseEntity<Map<String, Any>> {
        val agents = agentConfigurationService.getAllAgents()
        val results = mutableListOf<Map<String, String?>>()
        var successCount = 0
        var failCount = 0

        agents.forEach { agent ->
            val result = try {
                if (agent.enabled) {
                    val restarted = scrapingService.startWorker(agent)
                    if (restarted) {
                        successCount++
                        mapOf(
                            "agentId" to agent.id,
                            "status" to "restarted",
                            "message" to "Worker successfully restarted"
                        )
                    } else {
                        failCount++
                        mapOf(
                            "agentId" to agent.id,
                            "status" to "failed",
                            "message" to "Failed to restart worker"
                        )
                    }
                } else {
                    mapOf(
                        "agentId" to agent.id,
                        "status" to "skipped",
                        "message" to "Agent is disabled"
                    )
                }
            } catch (e: Exception) {
                failCount++
                mapOf(
                    "agentId" to agent.id,
                    "status" to "error",
                    "message" to e.message
                )
            }
            results.add(result)
        }
        return ResponseEntity.ok(
            mapOf(
                "timestamp" to Instant.now(),
                "action" to "refresh_all",
                "summary" to mapOf(
                    "total" to agents.size,
                    "success" to successCount,
                    "failed" to failCount,
                    "skipped" to (agents.size - successCount - failCount)
                ),
                "details" to results
            )
        )
    }

    /**
     * Получить статус всех воркеров
     */
    @GetMapping("/status")
    suspend fun getScraperStatus(): ResponseEntity<ScraperStatusDto> {
        val stats = scrapingService.getWorkerStats()

        // Преобразуем в DTO
        val workers = stats.map { (agentId, workerStats) ->
            WorkerStatsDto.fromWorkerStats(agentId, workerStats)
        }.toList()

        // Считаем сводную статистику
        val totalRuns = workers.sumOf { it.totalRuns }
        val totalSuccess = workers.sumOf { it.successfulRuns }
        val totalErrors = workers.sumOf { it.failedRuns }
        val workersWithErrors = workers.count { it.lastError != null }

        val summary = ScraperSummaryDto(
            totalRuns = totalRuns,
            totalSuccess = totalSuccess,
            totalErrors = totalErrors,
            overallSuccessRate = if (totalRuns > 0)
                String.format("%.2f", totalSuccess.toDouble() / totalRuns * 100).toDouble()
            else 0.0,
            workersWithErrors = workersWithErrors,
            healthyWorkers = workers.size - workersWithErrors
        )

        val status = ScraperStatusDto(
            activeWorkers = workers.size,
            totalAgents = workers.size,
            workers = workers,
            summary = summary
        )

        return ResponseEntity.ok(status)
    }

    /**
     * Получить статистику ошибок
     */
    @GetMapping("/errors")
    fun getErrorStats(): ResponseEntity<ErrorStatsDto> {
        val stats = scrapingService.getWorkerStats()

        val errors = stats.filter { it.value.lastError != null }
            .map { (agentId, workerStats) ->
                val total = workerStats.totalRuns
                val success = workerStats.successfulRuns
                val successRate = if (total > 0)
                    String.format("%.2f", success.toDouble() / total * 100).toDouble()
                else 0.0

                WorkerErrorDto(
                    agentId = agentId,
                    lastError = workerStats.lastError!!,
                    lastRunAt = workerStats.lastRunAt,
                    failedRuns = workerStats.failedRuns,
                    successRate = successRate
                )
            }

        return ResponseEntity.ok(
            ErrorStatsDto(
                totalErrors = errors.size,
                errors = errors
            )
        )
    }

    /**
     * Получить детальную статистику по конкретному воркеру
     */
    @GetMapping("/workers/{agentId}")
    fun getWorker(@PathVariable agentId: String): ResponseEntity<WorkerStatsDto> {
        val stats = scrapingService.getWorkerStats()
        val workerStats = stats[agentId]
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(WorkerStatsDto.fromWorkerStats(agentId, workerStats))
    }

    /**
     * Получить список всех активных воркеров
     */
    @GetMapping("/workers")
    fun getAllWorkers(): ResponseEntity<List<WorkerStatsDto>> {
        val stats = scrapingService.getWorkerStats()
        val workers = stats.map { (agentId, workerStats) ->
            WorkerStatsDto.fromWorkerStats(agentId, workerStats)
        }.toList()

        return ResponseEntity.ok(workers)
    }
}

/**
 * Статистика воркера
 */
data class WorkerStatsDto(
    val agentId: String,
    val startedAt: Instant,
    val lastRunAt: Instant?,
    val lastRunDurationMs: Long?,
    val lastError: String?,
    val totalRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int,
    val successRate: Double,
    val status: String,
) {
    companion object {
        fun fromWorkerStats(agentId: String, stats: com.metrada.scheduler.WorkerStats): WorkerStatsDto {
            val total = stats.totalRuns
            val success = stats.successfulRuns
            val successRate = if (total > 0) (success.toDouble() / total * 100) else 0.0

            val status = when {
                stats.lastError != null -> "ERROR"
                stats.lastRunAt == null -> "IDLE"
                else -> "RUNNING"
            }

            return WorkerStatsDto(
                agentId = agentId,
                startedAt = stats.startedAt,
                lastRunAt = stats.lastRunAt,
                lastRunDurationMs = stats.lastRunDuration?.toMillis(),
                lastError = stats.lastError,
                totalRuns = total,
                successfulRuns = success,
                failedRuns = stats.failedRuns,
                successRate = String.format("%.2f", successRate).toDouble(),
                status = status
            )
        }
    }
}

/**
 * Статистика ошибок
 */
data class ErrorStatsDto(
    val totalErrors: Int,
    val errors: List<WorkerErrorDto>,
)

/**
 * Детальная информация об ошибке
 */
data class WorkerErrorDto(
    val agentId: String,
    val lastError: String,
    val lastRunAt: Instant?,
    val failedRuns: Int,
    val successRate: Double,
)

/**
 * Общая статистика скраперов
 */
data class ScraperStatusDto(
    val activeWorkers: Int,
    val totalAgents: Int,
    val workers: List<WorkerStatsDto>,
    val summary: ScraperSummaryDto,
)

/**
 * Сводная статистика
 */
data class ScraperSummaryDto(
    val totalRuns: Int,
    val totalSuccess: Int,
    val totalErrors: Int,
    val overallSuccessRate: Double,
    val workersWithErrors: Int,
    val healthyWorkers: Int,
)

/**
 * Ответ при перезапуске/остановке
 */
data class WorkerActionResponseDto(
    val status: String,
    val agentId: String,
    val action: String,
    val timestamp: Instant = Instant.now(),
)