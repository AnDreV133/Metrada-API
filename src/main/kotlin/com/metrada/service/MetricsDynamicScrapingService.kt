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
import jakarta.annotation.PostConstruct
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
    private val workerPool: DynamicScraperWorkerPool  // новый пул
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(Dispatchers.Default)
    
    @PostConstruct
    fun init() {
        logger.info("Initializing MetricsScrapingService")
        
        // Регистрируем функцию скрапинга в пуле воркеров
        workerPool.setScraperFunction { agent ->
            scrapeAgent(agent)
        }
    }
    
    /**
     * Функция скрапинга для одного агента
     */
    private suspend fun scrapeAgent(agent: AgentEntity): List<MetricSampleModel> {
        logger.debug("Scraping agent: ${agent.id}")
        
        val agentConfig = AgentModel(
            id = agent.id,
            host = agent.host,
            port = agent.port,
            path = agent.path,
            scrapeIntervalSeconds = agent.scrapeIntervalSeconds,
            enabled = agent.enabled,
            timeoutSeconds = agent.timeoutSeconds
        )
        
        return try {
            val samples = agentScraperClient.scrapeAgent(agentConfig)
            
            if (samples.isNotEmpty()) {
                // Сохраняем метрики в фоне
                scope.launch {
                    saveMetrics(samples, agent)
                }
                
                // Обновляем статус агента
                updateAgentStatus(agent.id, "success")
            } else {
                updateAgentStatus(agent.id, "empty")
            }
            
            samples
        } catch (e: Exception) {
            logger.error("Failed to scrape agent ${agent.id}: ${e.message}")
            updateAgentStatus(agent.id, "failed: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Сохранение метрик в БД
     */
    @Transactional
    suspend fun saveMetrics(samples: List<MetricSampleModel>, agent: AgentEntity) {
        try {
            // Собираем все уникальные теги
            val allTagEntries = samples.flatMap { it.tags.entries }.distinct()
            
            // Находим или создаём теги
            val tagDictMap = findOrCreateTagDicts(allTagEntries)
            
            // Создаём метрики
            val metrics = samples.map { sample ->
                MetricEntity(
                    name = sample.name,
                    value = sample.value,
                    timestamp = sample.timestamp ?: Instant.now(),
                    agentId = agent.id,
                    metricTags = emptyList()
                )
            }
            
            val savedMetrics = metricRepository.saveAll(metrics)
            
            // Создаём связи с тегами
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
                    }
                }
            }
            
            if (metricTags.isNotEmpty()) {
                metricTagRepository.saveAll(metricTags)
            }
            
            logger.debug("Saved ${savedMetrics.size} metrics from agent ${agent.id}")
            
        } catch (e: Exception) {
            logger.error("Failed to save metrics: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Находит или создаёт теги в словаре
     */
    private fun findOrCreateTagDicts(tagEntries: List<Map.Entry<String, String>>): Map<String, TagDictEntity> {
        val result = mutableMapOf<String, TagDictEntity>()
        val toCreate = mutableListOf<Pair<String, String>>()
        
        // Сначала ищем существующие
        tagEntries.forEach { (key, value) ->
            val existing = tagDictRepository.findByKeyAndValue(key, value)
            if (existing != null) {
                result["$key=$value"] = existing
            } else {
                toCreate.add(key to value)
            }
        }
        
        // Создаём недостающие
        if (toCreate.isNotEmpty()) {
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
     * Обновляет статус агента
     */
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
     * Перезапустить воркер для агента
     */
    suspend fun restartWorker(agentId: String) = workerPool.restartWorker(agentId)
}