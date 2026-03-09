package com.metrada.client

import com.metrada.model.AgentModel
import com.metrada.model.MetricSampleModel
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.time.Instant

@Component
class AgentScraperClient {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    private val httpClient = HttpClient.create()
        .responseTimeout(Duration.ofSeconds(5))
        .followRedirect(true)
    
    suspend fun scrapeAgent(agent: AgentModel): List<MetricSampleModel> {
        val webClient = WebClient.builder()
            .baseUrl("http://${agent.host}:${agent.port}${agent.path}")
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
        
        return try {
            val response = webClient.get()
                .retrieve()
                .awaitBody<String>()
            
            parsePrometheusMetrics(response, agent)
        } catch (e: Exception) {
            logger.error("Failed to scrape agent ${agent.id}: ${e.message}")
            emptyList()
        }
    }
    
    private fun parsePrometheusMetrics(rawData: String, agent: AgentModel): List<MetricSampleModel> {
        val metrics = mutableListOf<MetricSampleModel>()
        val lines = rawData.lines()
        
        for (line in lines) {
            when {
                line.startsWith("#") -> continue // комментарии пропускаем
                line.isBlank() -> continue
                else -> {
                    // Простой парсер Prometheus формата: metric_name{tags} value [timestamp]
                    try {
                        val parts = line.split(" ")
                        if (parts.size >= 2) {
                            val namePart = parts[0]
                            val value = parts[1].toDouble()
                            
                            // Извлекаем имя метрики и теги
                            val (name, tags) = parseMetricNameAndTags(namePart)
                            
                            metrics.add(
                                MetricSampleModel(
                                    name = name,
                                    value = value,
                                    timestamp = if (parts.size >= 3) 
                                        Instant.ofEpochMilli(parts[2].toLong())
                                    else null,
                                    tags = tags + mapOf("agent_id" to agent.id)
                                )
                            )
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to parse metric line: $line")
                    }
                }
            }
        }
        
        return metrics
    }
    
    private fun parseMetricNameAndTags(input: String): Pair<String, Map<String, String>> {
        val tagStart = input.indexOf('{')
        if (tagStart == -1) return input to emptyMap()
        
        val name = input.substring(0, tagStart)
        val tagString = input.substring(tagStart + 1, input.lastIndexOf('}'))
        
        val tags = tagString.split(",")
            .map { it.split("=") }
            .filter { it.size == 2 }
            .associate { it[0] to it[1].trim('"') }
        
        return name to tags
    }
}