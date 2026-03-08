package com.metrada.service

import com.metrada.entity.AgentEntity
import com.metrada.entity.MetricEntity
import com.metrada.model.AgentsStatsModel
import com.metrada.repository.AgentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AgentConfigurationService(
    private val agentRepository: AgentRepository,
) {

    /**
     * Инициализация агентов по умолчанию (только если БД пуста)
     */
    @Transactional
    fun initializeDefaultAgents() {
        if (agentRepository.count() == 0L) {
            val defaultAgent = AgentEntity(
                id = "agent-1",
                host = "localhost",
                port = 9090,
                path = "/metrics",
                scrapeIntervalSeconds = 15,
                enabled = true,
                timeoutSeconds = 5,
                lastScrapeAt = null,
                lastScrapeStatus = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            agentRepository.save(defaultAgent)
        }
    }

    /**
     * Получить всех активных агентов (enabled = true)
     */
    fun getActiveAgents(): List<AgentEntity> {
        return agentRepository.findByEnabledTrue()
    }

    /**
     * Получить всех агентов
     */
    fun getAllAgents(): List<AgentEntity> {
        return agentRepository.findAll()
    }

    /**
     * Получить агента по ID
     */
    fun getAgent(id: String): AgentEntity? {
        return agentRepository.findById(id).orElse(null)
    }

    /**
     * Добавить нового агента
     */
    @Transactional
    fun addAgent(agent: AgentEntity): AgentEntity {
        // Проверяем, не существует ли уже агент с таким ID
        if (agentRepository.existsById(agent.id)) {
            throw IllegalArgumentException("Agent with id ${agent.id} already exists")
        }

        val newAgent = agent.copy(
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        return agentRepository.save(newAgent)
    }

    /**
     * Обновить существующего агента
     */
    @Transactional
    fun updateAgent(id: String, updatedAgent: AgentEntity): AgentEntity {
        val existingAgent = agentRepository.findById(id)
            .orElseThrow { IllegalArgumentException("Agent with id $id not found") }

        // Сохраняем исторические поля
        val agentToSave = updatedAgent.copy(
            createdAt = existingAgent.createdAt,  // не меняем дату создания
            updatedAt = Instant.now(),
            lastScrapeAt = existingAgent.lastScrapeAt,  // сохраняем историю скрапинга
            lastScrapeStatus = existingAgent.lastScrapeStatus
        )

        return agentRepository.save(agentToSave)
    }

    /**
     * Обновить ID агента (переименование)
     * Важно: метрики остаются со старым ID для сохранения истории
     */
    @Transactional
    fun changeAgentId(oldId: String, newId: String): AgentEntity {
        val agent = agentRepository.findById(oldId)
            .orElseThrow { IllegalArgumentException("Agent with id $oldId not found") }

        // Проверяем, что новый ID не занят
        if (agentRepository.existsById(newId)) {
            throw IllegalArgumentException("Agent with id $newId already exists")
        }

        // Создаём нового агента с новым ID
        val newAgent = agent.copy(
            id = newId,
            updatedAt = Instant.now()
        )

        // Удаляем старого
        agentRepository.delete(agent)

        // Сохраняем нового
        return agentRepository.save(newAgent)
    }

    /**
     * Удалить агента (мягкое удаление через enabled = false)
     */
    @Transactional
    fun softDeleteAgent(id: String): AgentEntity? {
        val agent = agentRepository.findById(id).orElse(null) ?: return null

        val disabledAgent = agent.copy(
            enabled = false,
            updatedAt = Instant.now()
        )

        return agentRepository.save(disabledAgent)
    }

    /**
     * Полное удаление агента из БД
     * Внимание: метрики остаются, но теряется связь с конфигурацией агента
     */
    @Transactional
    fun hardDeleteAgent(id: String): Boolean {
        return if (agentRepository.existsById(id)) {
            agentRepository.deleteById(id)
            true
        } else {
            false
        }
    }

    /**
     * Активировать/деактивировать агента
     */
    @Transactional
    fun setAgentEnabled(id: String, enabled: Boolean): AgentEntity? {
        val agent = agentRepository.findById(id).orElse(null) ?: return null

        val updatedAgent = agent.copy(
            enabled = enabled,
            updatedAt = Instant.now()
        )

        return agentRepository.save(updatedAgent)
    }

    /**
     * Обновить параметры скрапинга агента
     */
    @Transactional
    fun updateScrapingConfig(
        id: String,
        scrapeIntervalSeconds: Long? = null,
        timeoutSeconds: Int? = null,
        path: String? = null,
    ): AgentEntity? {
        val agent = agentRepository.findById(id).orElse(null) ?: return null

        val updatedAgent = agent.copy(
            scrapeIntervalSeconds = scrapeIntervalSeconds ?: agent.scrapeIntervalSeconds,
            timeoutSeconds = timeoutSeconds ?: agent.timeoutSeconds,
            path = path ?: agent.path,
            updatedAt = Instant.now()
        )

        return agentRepository.save(updatedAgent)
    }

    /**
     * Получить агентов с фильтрацией
     */
    fun findAgents(
        enabled: Boolean? = null,
        hostPattern: String? = null,
        minInterval: Long? = null,
    ): List<AgentEntity> {
        return agentRepository.findAll().filter { agent ->
            var matches = true

            if (enabled != null) {
                matches = matches && agent.enabled == enabled
            }

            if (hostPattern != null) {
                matches = matches && agent.host.contains(hostPattern, ignoreCase = true)
            }

            if (minInterval != null) {
                matches = matches && agent.scrapeIntervalSeconds >= minInterval
            }

            matches
        }
    }

    /**
     * Получить статистику по агентам todo: дополнить
     */
    fun getAgentsStats(): AgentsStatsModel {
        val allAgents = agentRepository.findAll()
        val activeAgents = allAgents.filter { it.enabled }

        return AgentsStatsModel(
            totalAgents = allAgents.size,
            activeAgents = activeAgents.size,
            inactiveAgents = allAgents.size - activeAgents.size,
            lastScrapeSuccess = allAgents.count { it.lastScrapeStatus == "success" },
            lastScrapeFailed = allAgents.count {
                it.lastScrapeStatus != null && it.lastScrapeStatus != "success"
            },
            neverScraped = allAgents.count { it.lastScrapeAt == null }
        )
    }
}