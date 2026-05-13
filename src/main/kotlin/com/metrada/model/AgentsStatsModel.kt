package com.metrada.model

/**
 * Статистика по агентам
 */
data class AgentsStatsModel(
    val totalAgents: Int,
    val activeAgents: Int,
    val inactiveAgents: Int,
)