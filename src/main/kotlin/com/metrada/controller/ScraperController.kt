package com.metrada.controller

import com.metrada.scheduler.DynamicScraperWorkerPool
import com.metrada.service.MetricsDynamicScrapingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/scrapers")
class ScraperController(
    private val workerPool: DynamicScraperWorkerPool,
    private val scrapingService: MetricsDynamicScrapingService,
) {

    /**
     * Получить статус всех воркеров
     */
    @GetMapping("/status")
    fun getScraperStatus(): Map<String, Any> {
        return mapOf(
            "activeWorkers" to workerPool.getWorkerStats().size,
            "workers" to workerPool.getWorkerStats(),
            "totalAgents" to workerPool.getWorkerStats().size  // упрощённо
        )
    }

    /**
     * Перезапустить воркер для конкретного агента
     */
    @PostMapping("/agents/{agentId}/restart")
    suspend fun restartWorker(@PathVariable agentId: String): ResponseEntity<Map<String, String>> {
        return if (scrapingService.restartWorker(agentId)) {
            ResponseEntity.ok(mapOf("status" to "restarted", "agentId" to agentId))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Остановить воркер для агента
     */
    @PostMapping("/agents/{agentId}/stop")
    fun stopWorker(@PathVariable agentId: String): ResponseEntity<Map<String, String>> {
        return if (workerPool.stopWorker(agentId)) {
            ResponseEntity.ok(mapOf("status" to "stopped", "agentId" to agentId))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Получить статистику ошибок
     */
    @GetMapping("/errors")
    fun getErrorStats(): Map<String, Any> {
        val stats = workerPool.getWorkerStats()
        val errors = stats.filter { it.value.lastError != null }
            .map { it.key to it.value.lastError }
            .toMap()

        return mapOf(
            "totalErrors" to errors.size,
            "errors" to errors
        )
    }
}