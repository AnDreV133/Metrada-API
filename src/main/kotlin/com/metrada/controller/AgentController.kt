package com.metrada.controller

import com.metrada.entity.AgentEntity
import com.metrada.service.AgentConfigurationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/v1/agents")
class AgentController(
    private val agentConfigurationService: AgentConfigurationService,
) {

    @GetMapping
    fun getAllAgents(@RequestParam enabled: Boolean? = null): List<AgentEntity> {
        return if (enabled != null) {
            agentConfigurationService.findAgents(enabled = enabled)
        } else {
            agentConfigurationService.getAllAgents()
        }
    }

    @GetMapping("/{id}")
    fun getAgent(@PathVariable id: String): ResponseEntity<AgentEntity> {
        return agentConfigurationService.getAgent(id)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    @PostMapping
    fun createAgent(@RequestBody request: CreateAgentRequest): ResponseEntity<AgentEntity> {
        val agent = AgentEntity(
            id = request.id,
            host = request.host,
            port = request.port,
            path = request.path ?: "/metrics",
            scrapeIntervalSeconds = request.scrapeIntervalSeconds ?: 15,
            enabled = request.enabled ?: true,
            timeoutSeconds = request.timeoutSeconds ?: 5,
            lastScrapeAt = null,
            lastScrapeStatus = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        return try {
            ResponseEntity.ok(agentConfigurationService.addAgent(agent))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        }
    }

    @PutMapping("/{id}")
    fun updateAgent(
        @PathVariable id: String,
        @RequestBody request: UpdateAgentRequest,
    ): ResponseEntity<AgentEntity> {
        val agent = AgentEntity(
            id = id,
            host = request.host,
            port = request.port,
            path = request.path,
            scrapeIntervalSeconds = request.scrapeIntervalSeconds,
            enabled = request.enabled,
            timeoutSeconds = request.timeoutSeconds,
            lastScrapeAt = null,  // эти поля не обновляются через PUT
            lastScrapeStatus = null,
            createdAt = Instant.now(),  // будет заменено в сервисе
            updatedAt = Instant.now()
        )

        return try {
            ResponseEntity.ok(agentConfigurationService.updateAgent(id, agent))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }

    @PatchMapping("/{id}/rename/{newId}")
    fun renameAgent(
        @PathVariable id: String,
        @PathVariable newId: String,
    ): ResponseEntity<AgentEntity> {
        return try {
            ResponseEntity.ok(agentConfigurationService.changeAgentId(id, newId))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }

    @PatchMapping("/{id}/toggle")
    fun toggleAgent(
        @PathVariable id: String,
        @RequestParam enabled: Boolean,
    ): ResponseEntity<AgentEntity> {
        return agentConfigurationService.setAgentEnabled(id, enabled)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    @DeleteMapping("/{id}")
    fun deleteAgent(
        @PathVariable id: String,
        @RequestParam hard: Boolean = false,
    ): ResponseEntity<Void> {
        val deleted = if (hard) {
            agentConfigurationService.hardDeleteAgent(id)
        } else {
            agentConfigurationService.softDeleteAgent(id) != null
        }

        return if (deleted) {
            ResponseEntity.ok().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/stats")
    fun getStats() = agentConfigurationService.getAgentsStats()
}

data class CreateAgentRequest(
    val id: String,
    val host: String,
    val port: Int,
    val path: String? = null,
    val scrapeIntervalSeconds: Long? = null,
    val enabled: Boolean? = null,
    val timeoutSeconds: Int? = null,
)

data class UpdateAgentRequest(
    val host: String,
    val port: Int,
    val path: String,
    val scrapeIntervalSeconds: Long,
    val enabled: Boolean,
    val timeoutSeconds: Int,
)