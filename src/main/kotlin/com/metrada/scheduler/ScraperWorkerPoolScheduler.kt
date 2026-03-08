package com.metrada.scheduler

import com.metrada.client.AgentScraperClient
import com.metrada.entity.AgentEntity
import com.metrada.model.AgentConfigModel
import com.metrada.model.MetricSampleModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ScraperWorkerPoolScheduler(
    private val maxConcurrentScrapes: Int = 10
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val semaphore = Semaphore(maxConcurrentScrapes)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Запускает непрерывный опрос агентов
     */
    fun startContinuousScraping(
        agentsProvider: () -> List<AgentEntity>,  // провайдер агентов
        scraper: suspend (AgentEntity) -> List<MetricSampleModel>,  // функция скрапинга
        intervalSeconds: Long = 15
    ): Job {
        return scope.launch {
            while (isActive) {
                try {
                    val agents = agentsProvider()

                    val startTime = System.currentTimeMillis()

                    // Параллельный опрос с ограничением
                    val metrics = scrapeWithConcurrencyLimit(agents, scraper)

                    val duration = System.currentTimeMillis() - startTime
                    logger.info("Scraped ${metrics.size} metrics from ${agents.size} agents in ${duration}ms")

                } catch (e: Exception) {
                    logger.error("Error in scraping cycle: ${e.message}", e)
                }

                delay(intervalSeconds * 1000)
            }
        }
    }

    /**
     * Опрос с ограничением параллелизма
     */
    private suspend fun scrapeWithConcurrencyLimit(
        agents: List<AgentEntity>,
        scraper: suspend (AgentEntity) -> List<MetricSampleModel>
    ): List<MetricSampleModel> = withContext(Dispatchers.IO) {
        val deferreds = agents.map { agent ->
            async {
                semaphore.withPermit {
                    try {
                        scraper(agent)  // ← здесь вызывается scrapeAndStore, который использует AgentScraperClient
                    } catch (e: Exception) {
                        logger.error("Error scraping agent ${agent.id}: ${e.message}")
                        emptyList()
                    }
                }
            }
        }

        deferreds.awaitAll().flatten()
    }
}