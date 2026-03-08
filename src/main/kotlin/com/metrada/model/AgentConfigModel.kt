package com.metrada.model

data class AgentConfigModel(
    val id: String,
    val host: String,
    val port: Int,
    val path: String = "/metrics",
    val scrapeIntervalSeconds: Long = 15,
    val enabled: Boolean = true,
    val timeoutSeconds: Int = 5
)