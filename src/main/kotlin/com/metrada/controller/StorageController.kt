package com.metrada.controller

import com.metrada.repository.MetricRepository
import com.metrada.service.AgentConfigurationService
import com.metrada.service.MetricsCleanupService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/storage")
@Deprecated("not used")
class StorageController(
    private val agentConfigurationService: AgentConfigurationService,
    private val metricsCleanupService: MetricsCleanupService,
    private val metricRepository : MetricRepository
) {
    /**
     * Получить информацию о значении retention для агента
     */
//    @GetMapping("/{id}/retention")
//    fun getRetentionInfo(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
//        val agent = agentConfigurationService.getAgent(id)
//            ?: return ResponseEntity.notFound().build()
//
//        val oldMetricsCount = metricRepository.countOldMetricsForAgent(
//            agentId = id,
//            olderThan = agent.getRetentionThreshold()
//        )
//
//        return ResponseEntity.ok(
//            mapOf(
//                "agentId" to id,
//                "retentionDays" to agent.retentionDays,
//                "retentionThreshold" to agent.getRetentionThreshold().toString(),
//                "oldMetricsCount" to oldMetricsCount
//            )
//        )
//    }


}