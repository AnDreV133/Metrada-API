package com.metrada.service

import com.metrada.client.AgentScraperClient
import com.metrada.entity.AgentEntity
import com.metrada.entity.MetricEntity
import com.metrada.entity.TagDictEntity
import com.metrada.entity.relation.MetricTagEntity
import com.metrada.model.AgentModel
import com.metrada.model.MetricSampleModel
import com.metrada.repository.AgentRepository
import com.metrada.repository.MetricRepository
import com.metrada.repository.MetricTagRepository
import com.metrada.repository.TagDictRepository
import com.metrada.scheduler.DynamicScraperWorkerPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class MetricsDynamicScrapingService(
    private val agentScraperClient: AgentScraperClient,
    private val metricRepository: MetricRepository,
    private val tagDictRepository: TagDictRepository,
    private val agentRepository: AgentRepository,
    private val metricTagRepository: MetricTagRepository,
    private val workerPool: DynamicScraperWorkerPool,
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
    private suspend fun scrapeAndSaveMetrics(agent: AgentEntity): List<MetricSampleModel> {
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

        return try {
            val samples = agentScraperClient.scrapeAgent(agentModel)

            if (samples.isNotEmpty()) {
                scope.launch {
                    try {
                        saveMetrics(samples, agent)
                    } catch (e: Exception) {
                        logger.error("Failed to save metrics: ${e.message}")
                    }
                }

                updateAgentStatus(agent.id, "success")
                logger.info("Successfully scraped ${samples.size} metrics from agent ${agent.id}")
            } else {
                updateAgentStatus(agent.id, "empty")
                logger.warn("Agent ${agent.id} returned empty metrics")
            }

            samples
        } catch (e: Exception) {
            logger.error("Failed to scrape agent ${agent.id}: ${e.message}")
            updateAgentStatus(agent.id, "failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Сохранение метрик в базу данных
     */
    @Transactional
    suspend fun saveMetrics(samples: List<MetricSampleModel>, agent: AgentEntity) {
        try {
            // Собираем все уникальные теги из всех метрик
            val allTagEntries = samples.flatMap { it.tags.entries }.distinct()

            // Находим или создаём теги в словаре
            val tagDictMap = findOrCreateTagDicts(allTagEntries)

            // Создаём метрики (без тегов пока)
            val metrics = samples.map { sample ->
                MetricEntity(
                    name = sample.name,
                    value = sample.value,
                    timestamp = sample.timestamp ?: Instant.now(),
                    agentId = agent.id,
                    metricTags = emptyList()  // теги добавим позже
                )
            }

            // Сохраняем метрики, чтобы получить ID
            val savedMetrics = metricRepository.saveAll(metrics)

            // Создаём связи метрик с тегами
            val metricTags = mutableListOf<MetricTagEntity>()

            savedMetrics.forEachIndexed { index, metric ->
                val sample = samples[index]
                sample.tags.forEach { (key, value) ->
                    val tagDict = tagDictMap["$key=$value"]
                    if (tagDict != null) {
                        metricTags.add(
                            MetricTagEntity(
                                metric = metric,
                                tagDict = tagDict
                            )
                        )
                    } else {
                        logger.warn("TagDict not found for $key=$value")
                    }
                }
            }

            // Сохраняем связи метрик с тегами
            if (metricTags.isNotEmpty()) {
                metricTagRepository.saveAll(metricTags)
            }

            logger.debug("Saved ${savedMetrics.size} metrics with ${metricTags.size} tags from agent ${agent.id}")

        } catch (e: Exception) {
            logger.error("Failed to save metrics for agent ${agent.id}: ${e.message}", e)
            throw e  // пробрасываем, чтобы транзакция откатилась
        }
    }

    /**
     * Находит или создаёт теги в словаре
     */
    private fun findOrCreateTagDicts(tagEntries: List<Map.Entry<String, String>>): Map<String, TagDictEntity> {
        val result = mutableMapOf<String, TagDictEntity>()
        val toCreate = mutableListOf<Pair<String, String>>()

        // Сначала ищем существующие теги
        tagEntries.forEach { (key, value) ->
            val existing = tagDictRepository.findByKeyAndValue(key, value)
            if (existing != null) {
                result["$key=$value"] = existing
            } else {
                toCreate.add(key to value)
            }
        }

        // Создаём недостающие теги
        if (toCreate.isNotEmpty()) {
            logger.debug("Creating ${toCreate.size} new tag dictionaries")
            val newTagDicts = toCreate.map { (key, value) ->
                TagDictEntity.fromKeyValue(key, value)
            }
            val saved = tagDictRepository.saveAll(newTagDicts)
            saved.forEach { tagDict ->
                result["${tagDict.tagKey}=${tagDict.tagValue}"] = tagDict
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