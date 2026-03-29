package com.metrada.controller

import com.metrada.entity.AgentEntity
import com.metrada.model.UpdateAgentModel
import com.metrada.service.AgentConfigurationService
import com.metrada.service.MetricsAutoHandlerService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/v1/agents")
class AgentController(
    private val agentConfigurationService: AgentConfigurationService,
    private val metricsAutoHandlerService: MetricsAutoHandlerService
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
    suspend fun createAgent(@RequestBody request: CreateAgentRequest): ResponseEntity<AgentEntity> {
        var agent = AgentEntity(
            host = request.host,
            port = request.port,
            id = request.run { id ?: "$host:$port" },
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
            agent = agentConfigurationService.addAgent(agent)

            metricsAutoHandlerService.startWorker(agent)

            ResponseEntity.ok(agent)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        }
    }

    @PatchMapping("/{id}")
    suspend fun patchAgent(
        @PathVariable id: String,
        @RequestBody request: UpdateAgentRequest,
    ): ResponseEntity<AgentEntity> {
        return try {
            val updatedAgent = agentConfigurationService.patchAgent(id, request)

            if (updatedAgent.enabled)
                metricsAutoHandlerService.startWorker(updatedAgent)
            else
                metricsAutoHandlerService.stopWorker(updatedAgent);

            ResponseEntity.ok(updatedAgent)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteAgent(
        @PathVariable id: String,
        @RequestParam hard: Boolean = false,
    ): ResponseEntity<Void> {
        return if (agentConfigurationService.deleteAgent(id)) {
            ResponseEntity.ok().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/stats")
    fun getStats() = agentConfigurationService.getAgentsStats()
}

typealias UpdateAgentRequest = UpdateAgentModel

data class CreateAgentRequest(
    val host: String,
    val port: Int,
    val id: String? = null,
    val path: String? = null,
    val scrapeIntervalSeconds: Long? = null,
    val enabled: Boolean? = null,
    val timeoutSeconds: Int? = null,
)

