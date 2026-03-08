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

    // Настраиваемые параметры
    private val maxConcurrentScrapes = 10
    private val discoveryIntervalMs = 30000L  // 30 секунд
    private val staleAgentThreshold = Duration.ofMinutes(5)

    // Состояние воркеров
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val workerJobs = ConcurrentHashMap<String, Job>()
    private val agentMutex = Mutex()
    private var discoveryJob: Job? = null

    // Статистика
    private val workerStats = ConcurrentHashMap<String, WorkerStats>()

    @PostConstruct
    fun start() {
        logger.info("Starting dynamic scraper worker pool")

        // Запускаем воркеры для существующих агентов
        startAllWorkers()

        // Запускаем фоновое обнаружение новых агентов
        startAgentDiscovery()
    }

    @PreDestroy
    fun stop() {
        logger.info("Stopping dynamic scraper worker pool")
        discoveryJob?.cancel()
        workerJobs.values.forEach { it.cancel() }
        workerJobs.clear()
        scope.cancel()
    }

    /**
     * Запускает воркеры для всех активных агентов
     */
    private fun startAllWorkers() {
        val activeAgents = agentConfigurationService.getActiveAgents()
        activeAgents.forEach { agent ->
            startWorkerForAgent(agent)
        }
        logger.info("Started ${workerJobs.size} workers for active agents")
    }

    /**
     * Запускает воркер для конкретного агента
     */
    private fun startWorkerForAgent(agent: AgentEntity): Job {
        return scope.launch {
            val workerId = agent.id
            logger.info("Starting worker for agent: ${agent.id} (${agent.host}:${agent.port})")

            workerStats[workerId] = WorkerStats(
                agentId = workerId,
                startedAt = Instant.now()
            )

            while (isActive) {
                val startTime = Instant.now()

                try {
                    // Проверяем, активен ли ещё агент
                    val currentAgent = agentConfigurationService.getAgent(agent.id)
                    if (currentAgent == null || !currentAgent.enabled) {
                        logger.info("Agent ${agent.id} is no longer active, stopping worker")
                        break
                    }

                    // Выполняем скрапинг
                    val scraper = getScraperForAgent(currentAgent)
                    val samples = scraper(currentAgent)

                    // Обновляем статистику
                    updateWorkerStats(workerId, success = true, duration = Duration.between(startTime, Instant.now()))

                    logger.debug("Worker ${agent.id} scraped ${samples.size} metrics")

                    // Ждём следующий интервал
                    val interval = currentAgent.scrapeIntervalSeconds * 1000
                    delay(interval)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Worker ${agent.id} error: ${e.message}")

                    // Обновляем статистику ошибок
                    updateWorkerStats(workerId, success = false, error = e.message)

                    // Экспоненциальная задержка при ошибках
                    val backoff = calculateBackoff(workerStats[workerId])
                    delay(backoff)
                }
            }

            logger.info("Worker for agent ${agent.id} stopped")
            workerJobs.remove(agent.id)
            workerStats.remove(agent.id)
        }.also {
            workerJobs[agent.id] = it
        }
    }

    /**
     * Фоновое обнаружение новых агентов
     */
    private fun startAgentDiscovery() {
        discoveryJob = scope.launch {
            while (isActive) {
                try {
                    discoverAndSyncAgents()
                } catch (e: Exception) {
                    logger.error("Agent discovery error: ${e.message}")
                }
                delay(discoveryIntervalMs)
            }
        }
    }

    /**
     * Обнаруживает новые агенты и синхронизирует состояние
     */
    private suspend fun discoverAndSyncAgents() {
        agentMutex.withLock {
            val activeAgents = agentConfigurationService.getActiveAgents()
            val activeAgentIds = activeAgents.map { it.id }.toSet()

            // 1. Запускаем воркеры для новых агентов
            activeAgents.forEach { agent ->
                if (!workerJobs.containsKey(agent.id)) {
                    logger.info("Discovered new agent: ${agent.id}")
                    startWorkerForAgent(agent)
                }
            }

            // 2. Останавливаем воркеры для удалённых/отключённых агентов
            workerJobs.keys.forEach { agentId ->
                if (agentId !in activeAgentIds) {
                    logger.info("Agent ${agentId} is no longer active, stopping worker")
                    workerJobs[agentId]?.cancel()
                }
            }

            // 3. Проверяем "зависшие" воркеры
            checkStaleWorkers()
        }
    }

    /**
     * Проверяет воркеры, которые давно не обновляли статистику
     */
    private suspend fun checkStaleWorkers() {
        val now = Instant.now()
        workerStats.forEach { (agentId, stats) ->
            if (stats.lastRunAt != null &&
                Duration.between(stats.lastRunAt, now) > staleAgentThreshold
            ) {

                logger.warn("Worker for agent ${agentId} appears stale, restarting")
                workerJobs[agentId]?.cancel()

                // Перезапустится при следующем discovery
            }
        }
    }

    /**
     * Обновляет статистику воркера
     */
    private fun updateWorkerStats(
        workerId: String,
        success: Boolean,
        duration: Duration? = null,
        error: String? = null,
    ) {
        workerStats.compute(workerId) { _, existing ->
            (existing ?: WorkerStats(agentId = workerId)).copy(
                lastRunAt = Instant.now(),
                lastRunDuration = duration,
                lastError = error,
                totalRuns = (existing?.totalRuns ?: 0) + 1,
                successfulRuns = (existing?.successfulRuns ?: 0) + if (success) 1 else 0,
                failedRuns = (existing?.failedRuns ?: 0) + if (success) 0 else 1
            )
        }
    }

    /**
     * Вычисляет экспоненциальную задержку при ошибках
     */
    private fun calculateBackoff(stats: WorkerStats?): Long {
        val failedCount = stats?.failedRuns ?: 0
        return when {
            failedCount <= 1 -> 1000L      // 1 секунда
            failedCount <= 3 -> 5000L      // 5 секунд
            failedCount <= 5 -> 15000L     // 15 секунд
            else -> 30000L                  // 30 секунд
        }
    }

    /**
     * Функция скрапинга (должна быть внедрена)
     */
    private suspend fun getScraperForAgent(agent: AgentEntity): suspend (AgentEntity) -> List<MetricSampleModel> {
        // Здесь должен быть внедрён реальный скрапер
        // Например, через DI или callback
        return { agent ->
            // Реализация будет добавлена через setScraperFunction
            emptyList()
        }
    }

    // Для внедрения реальной функции скрапинга
    private var scraperFunction: (suspend (AgentEntity) -> List<MetricSampleModel>)? = null

    fun setScraperFunction(function: suspend (AgentEntity) -> List<MetricSampleModel>) {
        this.scraperFunction = function
    }

    /**
     * Получить статистику всех воркеров
     */
    fun getWorkerStats(): Map<String, WorkerStats> = workerStats.toMap()

    /**
     * Принудительно перезапустить воркер для агента
     */
    suspend fun restartWorker(agentId: String): Boolean {
        agentMutex.withLock {
            workerJobs[agentId]?.cancel()
            workerJobs.remove(agentId)

            val agent = agentConfigurationService.getAgent(agentId)
            if (agent != null && agent.enabled) {
                startWorkerForAgent(agent)
                return true
            }
            return false
        }
    }

    /**
     * Остановить воркер для агента
     */
    fun stopWorker(agentId: String): Boolean {
        return workerJobs[agentId]?.cancel() != null
    }
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