//package com.metrada.service
//
//import com.metrada.client.AgentScraperClient
//import com.metrada.entity.AgentEntity
//import com.metrada.entity.MetricEntity
//import com.metrada.entity.TagDictEntity
//import com.metrada.entity.relation.MetricTagEntity
//import com.metrada.model.AgentConfigModel  // возможно, это DTO, а не entity
//import com.metrada.model.MetricSampleModel
//import com.metrada.repository.AgentRepository
//import com.metrada.repository.MetricRepository
//import com.metrada.repository.TagDictRepository
//import com.metrada.scheduler.ScraperWorkerPoolScheduler
//import jakarta.annotation.PostConstruct
//import jakarta.annotation.PreDestroy
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import org.slf4j.LoggerFactory
//import org.springframework.stereotype.Service
//import org.springframework.transaction.annotation.Transactional
//import java.time.Instant

//@Service
@Deprecated("no valid")
class MetricsScrapingService_()
//    private val agentScraperClient: AgentScraperClient,
//    private val metricRepository: MetricRepository,
//    private val tagDictRepository: TagDictRepository,
//    private val agentRepository: AgentRepository,
//    private val agentConfigurationService: AgentConfigurationService,
//    private val workerPool: ScraperWorkerPoolScheduler
//) {
//    private val logger = LoggerFactory.getLogger(javaClass)
//    private val scope = CoroutineScope(Dispatchers.Default)
//    private var scrapingJob: Job? = null
//
//    @PostConstruct
//    fun startScraping() {
//        logger.info("Starting continuous metrics scraping...")
//
//        // Инициализируем тестовых агентов, если БД пуста
//        agentConfigurationService.initializeDefaultAgents()
//
//        scrapingJob = workerPool.startContinuousScraping(
//            agentsProvider = { agentConfigurationService.getActiveAgents() },
//            scraper = { agent ->
//                scrapeAndStore(agent as AgentEntity)
//            },
//            intervalSeconds = 30
//        )
//    }
//
//    @PreDestroy
//    fun stopScraping() {
//        logger.info("Stopping metrics scraping...")
//        scrapingJob?.cancel()
//    }
//
//    /**
//     * Опрашивает одного агента и сохраняет метрики
//     */
//    private suspend fun scrapeAndStore(agent: AgentEntity): List<MetricSampleModel> {
//        return try {
//            // Конвертируем AgentEntity в AgentConfigModel для клиента
//            val agentConfig = AgentConfigModel(
//                id = agent.id,
//                host = agent.host,
//                port = agent.port,
//                path = agent.path,
//                scrapeIntervalSeconds = agent.scrapeIntervalSeconds,
//                enabled = agent.enabled,
//                timeoutSeconds = agent.timeoutSeconds
//            )
//
//            val samples = agentScraperClient.scrapeAgent(agentConfig)
//
//            if (samples.isNotEmpty()) {
//                saveMetrics(samples, agent)
//
//                // Обновляем статус последнего скрапинга
//                updateAgentScrapeStatus(agent.id, "success")
//            } else {
//                updateAgentScrapeStatus(agent.id, "empty")
//            }
//
//            samples
//        } catch (e: Exception) {
//            logger.error("Failed to scrape agent ${agent.id}: ${e.message}")
//            updateAgentScrapeStatus(agent.id, "failed: ${e.message}")
//            emptyList()
//        }
//    }
//
//    /**
//     * Обновляет статус последнего скрапинга агента
//     */
//    private fun updateAgentScrapeStatus(agentId: String, status: String) {
//        scope.launch(Dispatchers.IO) {
//            try {
//                agentRepository.updateScrapeStatus(agentId, Instant.now(), status)
//            } catch (e: Exception) {
//                logger.error("Failed to update agent status: ${e.message}")
//            }
//        }
//    }
//
//    /**
//     * Сохраняет метрики в базу данных с тегами
//     */
//    @Transactional
//    suspend fun saveMetrics(samples: List<MetricSampleModel>, agent: AgentEntity): List<MetricEntity> =
//        withContext(Dispatchers.IO) {
//            try {
//                val metrics = mutableListOf<MetricEntity>()
//
//                // Группируем все уникальные теги из всех метрик
//                val allTagEntries = samples.flatMap { it.tags.entries }
//                    .distinct()
//
//                // Находим или создаём все теги одной операцией
//                val tagDictMap = findOrCreateTagDicts(allTagEntries)
//
//                // Сохраняем метрики
//                samples.forEach { sample ->
//                    val metric = MetricEntity(
//                        name = sample.name,
//                        value = sample.value,
//                        timestamp = sample.timestamp ?: Instant.now(),
//                        agentId = agent.id,
//                        metricTags = emptyList()  // временно
//                    )
//
//                    val savedMetric = metricRepository.save(metric)
//
//                    // Создаём связи с тегами
//                    val metricTags = sample.tags.map { (key, value) ->
//                        val tagDict = tagDictMap["$key=$value"]
//                            ?: error("TagDict not found for $key=$value")
//
//                        MetricTagEntity(
//                            metric = savedMetric,
//                            tagDict = tagDict
//                        )
//                    }
//
//                    // Обновляем метрику с тегами
//                    // В реальности нужно сохранять MetricTagEntity через отдельный репозиторий
//                    // или через каскадное сохранение
//                    metrics.add(savedMetric.copy(metricTags = metricTags))
//                }
//
//                logger.debug("Saved ${metrics.size} metrics from agent ${agent.id}")
//                metrics
//
//            } catch (e: Exception) {
//                logger.error("Failed to save metrics for agent ${agent.id}: ${e.message}", e)
//                emptyList()
//            }
//        }
//
//    /**
//     * Находит или создаёт записи в словаре тегов
//     */
//    private fun findOrCreateTagDicts(tagEntries: List<Map.Entry<String, String>>): Map<String, TagDictEntity> {
//        // Ищем существующие теги
//        val existingTags = tagEntries.mapNotNull { (key, value) ->
//            tagDictRepository.findByKeyAndValue(key, value)?.let {
//                "$key=$value" to it
//            }
//        }.toMap()
//
//        // Определяем, каких тегов не хватает
//        val missingTags = tagEntries.filter { (key, value) ->
//            "$key=$value" !in existingTags
//        }
//
//        // Создаём недостающие теги
//        val newTags = if (missingTags.isNotEmpty()) {
//            val newTagDicts = missingTags.map { (key, value) ->
//                TagDictEntity.fromKeyValue(key, value)
//            }
//            tagDictRepository.saveAll(newTagDicts).associateBy {
//                "${it.tagKey}=${it.tagValue}"
//            }
//        } else {
//            emptyMap()
//        }
//
//        return existingTags + newTags
//    }
//
//    /**
//     * Принудительный опрос конкретного агента
//     */
//    suspend fun scrapeAgentNow(agentId: String): List<MetricEntity> {
//        val agent = agentRepository.findById(agentId).orElse(null)
//            ?: throw IllegalArgumentException("Agent $agentId not found")
//
//        val samples = agentScraperClient.scrapeAgent(
//            AgentConfigModel(
//                id = agent.id,
//                host = agent.host,
//                port = agent.port,
//                path = agent.path,
//                scrapeIntervalSeconds = agent.scrapeIntervalSeconds,
//                enabled = agent.enabled,
//                timeoutSeconds = agent.timeoutSeconds
//            )
//        )
//
//        return saveMetrics(samples, agent)
//    }
//}