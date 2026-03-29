package com.metrada.client

import com.metrada.model.AgentModel
import com.metrada.model.MetricSampleModel
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
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
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)
            }
            .build()

        return try {

            webClient.get()
                .retrieve()
                .bodyToFlux<DataBuffer>()
                .limitRate(100)
                .collect({ StringBuilder() }) { sb, chunk ->
                    sb.append(chunk.toString(Charsets.UTF_8))
                }
                .map {
                    parsePrometheusMetrics(it.toString(), agent)
                }
                .awaitFirstOrNull() ?: emptyList()

        } catch (e: Exception) {
            logger.error("Failed to scrape agent ${agent.id}: ${e.message}")
            emptyList()
        }
    }

    private fun parsePrometheusMetrics(rawData: String, agent: AgentModel): List<MetricSampleModel> {
        val metrics = mutableListOf<MetricSampleModel>()
        val lines = rawData.lines() // todo very slow!

        val currentTimestamp = Instant.now()

        for (line in lines) {
            when {
                line.startsWith("#") -> continue // комментарии пропускаем
                line.isBlank() -> continue
                else -> {
                    try {
                        val parts = line.split(" ")
                        if (parts.size >= 2) {
                            val namePart = parts[0]
                            val value = parts[1].toDouble()

                            val (name, tags) = parseMetricNameAndTags(namePart)

                            metrics.add(
                                MetricSampleModel(
                                    name = name,
                                    value = value,
                                    timestamp = currentTimestamp,
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
        val tagString = input.substring(tagStart + 1, input.lastIndexOf('}') + 1)

        val tags = mutableMapOf<String, String>()
        val sbKey = StringBuffer()
        val sbValue = StringBuffer()
        var inValueQuotes = false
        tagString.forEach { c ->
            if (!inValueQuotes && c in listOf('=', ' ')) {
                // skip
            } else if (!inValueQuotes && c in listOf(',', '}')) {
                tags[sbKey.toString()] = sbValue.toString()
                sbKey.setLength(0)
                sbValue.setLength(0)
            } else if (!inValueQuotes && c == '"') {
                inValueQuotes = true
            } else if (inValueQuotes && c == '"') {
                inValueQuotes = false
            } else if (inValueQuotes) sbValue.append(c)
            else sbKey.append(c)
        }
//        val tags = tagString.split(",")
//            .map { it.split("=") }
//            .filter { it.size == 2 }
//            .associate { it[0] to it[1].trim('"') }

        return name to tags
    }
}