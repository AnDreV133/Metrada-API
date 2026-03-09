package com.metrada.service

import com.metrada.entity.AgentEntity
import com.metrada.entity.MetricEntity
import com.metrada.entity.TagDictEntity
import com.metrada.entity.relation.MetricTagEntity
import com.metrada.repository.AgentRepository
import com.metrada.repository.MetricRepository
import com.metrada.repository.TagDictRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Deprecated("using other services")
@Service
class MonitoringService(
    private val agentRepository: AgentRepository,
    private val metricRepository: MetricRepository,
    private val tagDictRepository: TagDictRepository,
) {

    /**
     * Сохранение метрики
     */
    @Transactional
    fun saveMetric(
        name: String,
        value: Double,
        agentId: String,
        host: String,
        tags: Map<String, String> = emptyMap(),
        timestamp: Instant = Instant.now(),
    ): MetricEntity {

        // Создаём метрику (agent_id хранится как строка)
        val metric = MetricEntity(
            name = name,
            value = value,
            timestamp = timestamp,
            agentId = agentId,
            metricTags = emptyList()
        )

        val savedMetric = metricRepository.save(metric)

        // Добавляем теги
        if (tags.isNotEmpty()) {
            val metricTags = createMetricTags(savedMetric, tags)
            return savedMetric.copy(metricTags = metricTags)
        }

        return savedMetric
    }

    /**
     * Обновление ID агента (например, при переименовании)
     * Метрики сохраняются со старым ID
     */
    @Transactional
    fun changeAgentId(oldId: String, newId: String): AgentEntity? {
        val agent = agentRepository.findById(oldId).orElse(null) ?: return null

        // Создаём нового агента с новым ID
        val newAgent = agent.copy(
            id = newId,
            updatedAt = Instant.now()
        )

        // Удаляем старого агента
        agentRepository.delete(agent)

        // Сохраняем нового
        return agentRepository.save(newAgent)
    }

    /**
     * Удаление агента
     * Метрики НЕ удаляются - история сохраняется
     */
    @Transactional
    fun deleteAgent(agentId: String) {
        agentRepository.findById(agentId).ifPresent { agent ->
            agentRepository.delete(agent)
        }
    }

    /**
     * Получение метрик агента (даже если агент удалён)
     */
    fun getAgentMetrics(agentId: String, start: Instant, end: Instant): List<MetricEntity> {
        return metricRepository.findByAgentIdAndTimeRange(agentId, start, end)
    }

    private fun createMetricTags(metric: MetricEntity, tags: Map<String, String>): List<MetricTagEntity> {
        // Находим или создаём теги
        val tagDicts = tags.map { (key, value) ->
            tagDictRepository.findByKeyAndValue(key, value)
                ?: tagDictRepository.save(TagDictEntity.fromKeyValue(key, value))
        }

        // Создаём связи
        return tagDicts.map { tagDict ->
            MetricTagEntity(
                metric = metric,
                tagDict = tagDict
            )
        }
    }
}