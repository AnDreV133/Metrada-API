package com.metrada.service

import com.metrada.entity.AgentEntity
import com.metrada.model.AgentsStatsModel
import com.metrada.model.UpdateAgentModel
import com.metrada.repository.AgentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AgentConfigurationService(
    private val agentRepository: AgentRepository,
) {
    init {
        initializeDefaultAgents()
    }

    /**
     * Инициализация агентов по умолчанию (только если БД пуста)
     */
    @Transactional
    private fun initializeDefaultAgents() {
        if (agentRepository.count() == 0L) {
            val defaultAgent = AgentEntity(
                id = "agent-default",
                host = "localhost",
                port = 9182,
                path = "/metrics",
                scrapeIntervalSeconds = 15,
                enabled = true,
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
     * НОВЫЙ МЕТОД: Патч агента с обновлением полей и опциональным переименованием
     * Использует существующие методы updateAgent и changeAgentId
     */
    @Transactional
    fun patchAgent(id: String, model: UpdateAgentModel): AgentEntity {
        val existingAgent = getAgent(id)
            ?: throw IllegalArgumentException("Agent with id $id not found")

        var updatedAgent = existingAgent.copy(
            host = model.host ?: existingAgent.host,
            port = model.port ?: existingAgent.port,
            path = model.path ?: existingAgent.path,
            scrapeIntervalSeconds = model.scrapeIntervalSeconds ?: existingAgent.scrapeIntervalSeconds,
            enabled = model.enabled ?: existingAgent.enabled,
            updatedAt = Instant.now()
        )

        if (updatedAgent != existingAgent)
            updatedAgent = updateAgent(id, updatedAgent)

        if (model.id != null && model.id != id)
            updatedAgent = changeAgentId(id, model.id)

        return updatedAgent
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
     * Полное удаление агента из БД
     */
    @Transactional
    fun deleteAgent(id: String): Boolean {
        return if (agentRepository.existsById(id)) {
            agentRepository.deleteById(id)
            true
        } else {
            false
        }
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
        )
    }
}