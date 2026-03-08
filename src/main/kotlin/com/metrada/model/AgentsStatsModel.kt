package com.metrada.model

/**
 * Статистика по агентам
 */
data class AgentsStatsModel(
    val totalAgents: Int,
    val activeAgents: Int,
    val inactiveAgents: Int,
    val lastScrapeSuccess: Int,
    val lastScrapeFailed: Int,
    val neverScraped: Int
)