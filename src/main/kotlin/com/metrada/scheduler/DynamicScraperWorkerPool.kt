package com.metrada.scheduler

import com.metrada.entity.AgentEntity
import com.metrada.model.MetricSampleModel
import com.metrada.service.AgentConfigurationService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class DynamicScraperWorkerPool(
    private val agentConfigurationService: AgentConfigurationService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val workerJobs = ConcurrentHashMap<String, Job>()
    private val agentMutex = Mutex()
    private val workerStats = ConcurrentHashMap<String, WorkerStats>()

    var scraperFunction: (suspend (AgentEntity) -> List<MetricSampleModel>)? = null

    @PostConstruct
    fun start() {
        logger.info("Starting scraper workers for existing agents")
        scope.launch {
            agentConfigurationService.getActiveAgents().forEach { agent ->
                startWorkerForAgent(agent)
            }
        }
    }

    @PreDestroy
    fun stop() {
        logger.info("Stopping all scraper workers")
        workerJobs.values.forEach { it.cancel() }
        workerJobs.clear()
        scope.cancel()
    }

    /**
     * Запустить воркер для агента (возвращает true если запущен новый воркер)
     * true - запущен новый воркер (агент был выключен или новый)
     * false - воркер уже был запущен (повторный запуск)
     */
    suspend fun startWorkerForAgent(agent: AgentEntity): Boolean = agentMutex.withLock {
        // Проверяем, есть ли уже запущенный воркер
        val existingJob = workerJobs[agent.id]

        if (existingJob != null && existingJob.isActive) {
            logger.info("Worker for agent ${agent.id} is already running")
            return@withLock false  // воркер уже запущен
        }

        if (!agent.enabled) {
            logger.info("Agent ${agent.id} is disabled, not starting worker")
            return@withLock false
        }

        // Останавливаем существующий воркер, если есть (но он должен быть уже остановлен)
        existingJob?.cancel()
        workerJobs.remove(agent.id)

        logger.info("Starting worker for agent: ${agent.id} (${agent.host}:${agent.port})")
        workerStats[agent.id] = WorkerStats(agentId = agent.id, startedAt = Instant.now())

        // Создаём и запускаем новый воркер
        val job = createWorkerJob(agent)
        workerJobs[agent.id] = job

        true  // запущен новый воркер
    }

    /**
     * Остановить воркер для агента (возвращает true если воркер был остановлен)
     * true - воркер найден и остановлен
     * false - воркер не найден (не был запущен)
     */
    suspend fun stopWorkerForAgent(agent: AgentEntity): Boolean = agentMutex.withLock {
        val job = workerJobs[agent.id]

        if (job != null && job.isActive) {
            job.cancel()
            workerJobs.remove(agent.id)
            workerStats.remove(agent.id)
            logger.info("Stopped worker for agent ${agent.id}")
            return@withLock true  // воркер остановлен
        } else {
            logger.info("No active worker found for agent ${agent.id}")
            return@withLock false  // воркер не найден
        }
    }

    /**
     * Внутренний метод для создания воркера
     */
    private fun createWorkerJob(agent: AgentEntity): Job = scope.launch {
        while (isActive) {
            try {
                val currentAgent = agentConfigurationService.getAgent(agent.id)
                if (currentAgent == null || !currentAgent.enabled) {
                    logger.info("Agent ${agent.id} is no longer active, stopping worker")
                    break
                }

                scraperFunction?.invoke(currentAgent)
                delay(currentAgent.scrapeIntervalSeconds * 1000)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Worker ${agent.id} error: ${e.message}")
                delay(5000)
            }
        }

        // Очистка при завершении
        agentMutex.withLock {
            workerJobs.remove(agent.id)
            workerStats.remove(agent.id)
        }
        logger.info("Worker for agent ${agent.id} stopped")
    }

    fun getWorkerStats(): Map<String, WorkerStats> = workerStats.toMap()

    fun isWorkerRunning(agentId: String): Boolean = workerJobs.containsKey(agentId)
}

/**
 * Статистика работы воркера
 */
data class WorkerStats(
    val agentId: String,
    val startedAt: Instant = Instant.now(),
    var lastRunAt: Instant? = null,
    var lastRunDuration: Duration? = null,
    var lastError: String? = null,
    var totalRuns: Int = 0,
    var successfulRuns: Int = 0,
    var failedRuns: Int = 0,
)