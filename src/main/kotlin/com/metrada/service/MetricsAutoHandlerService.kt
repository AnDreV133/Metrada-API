package com.metrada.service

import com.metrada.client.AgentScraperClient
import com.metrada.entity.AgentEntity
import com.metrada.entity.LabelDictEntity
import com.metrada.entity.MetricEntity
import com.metrada.entity.relation.MetricLabelEntity
import com.metrada.model.AgentModel
import com.metrada.model.MetricSampleModel
import com.metrada.repository.*
import com.metrada.util.chunked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class MetricsAutoHandlerService(
    private val agentScraperClient: AgentScraperClient,
    private val metricRepository: MetricRepository,
    private val labelDictRepository: LabelDictRepository,
    private val agentRepository: AgentRepository,
    private val metricLabelRepository: MetricLabelRepository,
    private val workerPool: WorkerPoolService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        logger.info("Initializing MetricsDynamicScrapingService")

        // Регистрируем функцию скрапинга в пуле воркеров
        workerPool.scraperFunction = ::scrapeAndSaveMetrics
    }

    /**
     * Основная функция скрапинга - вызывается воркером для каждого агента
     */
    @Transactional
    private suspend fun scrapeAndSaveMetrics(agent: AgentEntity) {
        logger.debug("Scraping agent: ${agent.id} (${agent.host}:${agent.port})")

        // Конвертируем AgentEntity в AgentModel для клиента
        val agentModel = AgentModel(
            id = agent.id,
            host = agent.host,
            port = agent.port,
            path = agent.path,
            scrapeIntervalSeconds = agent.scrapeIntervalSeconds,
            enabled = agent.enabled,
            timeoutSeconds = agent.timeoutSeconds
        )

        try {
            agentScraperClient.getMetricSampleFlow(agentModel)
                .chunked(20)
                .collect { samples ->
                    try {
                        saveMetrics(samples, agent)
                    } catch (e: Exception) {
                        logger.error("Failed to save metrics: ${e.message}")
                    }
                }

//            if (samples.isNotEmpty()) {
//            scope.launch {
//                try {
//                    saveMetrics(samples, agent)
//                } catch (e: Exception) {
//                    logger.error("Failed to save metrics: ${e.message}")
//                }
//            }

//                updateAgentStatus(agent.id, "success")
//                logger.info("Successfully scraped ${samples.size} metrics from agent ${agent.id}")
//            } else {
//                updateAgentStatus(agent.id, "empty")
//                logger.warn("Agent ${agent.id} returned empty metrics")
//            }
        } catch (e: Exception) {
            logger.error("Failed to scrape agent ${agent.id}: ${e.message}")
            updateAgentStatus(agent.id, "failed: ${e.message}")
        }
    }

    /**
     * Сохранение метрик в базу данных
     */
    @Transactional
    suspend fun saveMetrics(samples: List<MetricSampleModel>, agent: AgentEntity) {
        try {
            // Собираем все уникальные теги из всех метрик
            val allLabelEntries = samples.flatMap { it.labels.entries }.distinct()

            // Находим или создаём теги в словаре
            val labelDictMap = findOrCreateLabelDicts(allLabelEntries)

            // Создаём метрики (без тегов пока)
            val metrics = samples.map { sample ->
                MetricEntity(
                    value = sample.value,
                    timestamp = sample.timestamp ?: Instant.now(),
                    agentId = agent.id,
                    hash = sample.hash,
                    metricLabels = emptyList()  // теги добавим позже
                )
            }

            // Сохраняем метрики, чтобы получить ID
            val savedMetrics = metricRepository.saveAll(metrics)

            // Создаём связи метрик с тегами
            val metricLabels = mutableListOf<MetricLabelEntity>()

            savedMetrics.forEachIndexed { index, metric ->
                val sample = samples[index]
                sample.labels.forEach { (key, value) ->
                    val labelDict = labelDictMap["$key=$value"]
                    if (labelDict != null) {
                        metricLabels.add(
                            MetricLabelEntity(
                                metric = metric,
                                labelDict = labelDict
                            )
                        )
                    } else {
                        logger.warn("LabelDict not found for $key=$value")
                    }
                }
            }

            // Сохраняем связи метрик с тегами
            if (metricLabels.isNotEmpty()) {
                metricLabelRepository.saveAll(metricLabels)
            }

            logger.debug("Saved ${savedMetrics.size} metrics with ${metricLabels.size} labels from agent ${agent.id}")

        } catch (e: Exception) {
            logger.error("Failed to save metrics for agent ${agent.id}: ${e.message}", e)
            throw e  // пробрасываем, чтобы транзакция откатилась
        }
    }

    /**
     * Находит или создаёт теги в словаре
     */
    private fun findOrCreateLabelDicts(labelEntries: List<Map.Entry<String, String>>): Map<String, LabelDictEntity> {
        val result = mutableMapOf<String, LabelDictEntity>()
        val toCreate = mutableListOf<Pair<String, String>>()

        // Сначала ищем существующие теги
        labelEntries.forEach { (key, value) ->
            val existing = labelDictRepository.findByKeyAndValue(key, value)
            if (existing != null) {
                result["$key=$value"] = existing
            } else {
                toCreate.add(key to value)
            }
        }

        // Создаём недостающие теги
        if (toCreate.isNotEmpty()) {
            logger.debug("Creating ${toCreate.size} new label dictionaries")
            val newLabelDicts = toCreate.map { (key, value) ->
                LabelDictEntity.fromKeyValue(key, value)
            }
            val saved = labelDictRepository.saveAll(newLabelDicts)
            saved.forEach { labelDict ->
                result["${labelDict.labelKey}=${labelDict.labelValue}"] = labelDict
            }
        }

        return result
    }

    /**
     * Обновляет статус последнего скрапинга агента
     */
    @Transactional
    private fun updateAgentStatus(agentId: String, status: String) {
        scope.launch {
            try {
                agentRepository.updateScrapeStatus(agentId, Instant.now(), status)
            } catch (e: Exception) {
                logger.error("Failed to update agent status: ${e.message}")
            }
        }
    }

    /**
     * Получить статистику работы воркеров
     */
    fun getWorkerStats() = workerPool.getWorkerStats()

    /**
     * Запустить воркер для агента
     */
    suspend fun startWorker(agent: AgentEntity) = workerPool.startWorkerForAgent(agent)

    /**
     * Остановить воркер для агента
     */
    suspend fun stopWorker(agent: AgentEntity) = workerPool.stopWorkerForAgent(agent)
}