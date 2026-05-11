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
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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

    private val cache = ConcurrentHashMap<String, LabelDictEntity>()

    @PostConstruct
    fun start() {
        logger.info("Initializing MetricsDynamicScrapingService")

        logger.info("Loading label dictionary into cache...")
        val allLabels = labelDictRepository.findAll()
        allLabels.forEach { label ->
            cache["${label.labelKey}=${label.labelValue}"] = label
        }
        logger.info("Loaded ${allLabels.size} labels into cache")

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
            val labelDictMap = findOrCreateBatch(allLabelEntries)

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
     * Находит или создаёт LabelDictEntity
     */
    suspend fun findOrCreate(labelKey: String, labelValue: String): LabelDictEntity {
        val key = "$labelKey=$labelValue"

        // Проверяем кэш
        cache[key]?.let { return it }

        // Проверяем БД
        val hash = LabelDictEntity.generateHash(labelKey, labelValue)
        val existing = labelDictRepository.findByHash(hash)
        if (existing != null) {
            cache[key] = existing
            return existing
        }

        // Создаём новую запись
        val newLabel = LabelDictEntity(
            labelKey = labelKey,
            labelValue = labelValue,
            hash = hash
        )
        val saved = labelDictRepository.save(newLabel)
        cache[key] = saved
        logger.debug("Created new label: $key")
        return saved
    }

    /**
     * Пакетное нахождение или создание лейблов
     */
    suspend fun findOrCreateBatch(labelEntries: List<Map.Entry<String, String>>): Map<String, LabelDictEntity> {
        val result = mutableMapOf<String, LabelDictEntity>()
        val missing = mutableListOf<Triple<String, String, String>>()

        // Сначала проверяем кэш
        for (entry in labelEntries) {
            val key = "${entry.key}=${entry.value}"
            val cached = cache[key]
            if (cached != null) {
                result[key] = cached
            } else {
                missing.add(Triple(key, entry.key, entry.value))
            }
        }

        if (missing.isNotEmpty()) {
            // Проверяем в БД все отсутствующие
            val hashes = missing.map { LabelDictEntity.generateHash(it.second, it.third) }
            val existingMap = labelDictRepository.findAllByHashIn(hashes)
                .associateBy { "${it.labelKey}=${it.labelValue}" }

            // Обновляем кэш существующими
            existingMap.forEach { (key, label) ->
                cache[key] = label
                result[key] = label
            }

            // Создаём действительно новые
            val toCreate = missing.filter { (key, _, _) -> key !in existingMap }
            if (toCreate.isNotEmpty()) {
                val newLabels = toCreate.map { (_, labelKey, labelValue) ->
                    LabelDictEntity(
                        labelKey = labelKey,
                        labelValue = labelValue,
                        hash = LabelDictEntity.generateHash(labelKey, labelValue)
                    )
                }
                val savedLabels = labelDictRepository.saveAll(newLabels)
                savedLabels.forEach { label ->
                    val key = "${label.labelKey}=${label.labelValue}"
                    cache[key] = label
                    result[key] = label
                }
                logger.debug("Created ${savedLabels.size} new labels")
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