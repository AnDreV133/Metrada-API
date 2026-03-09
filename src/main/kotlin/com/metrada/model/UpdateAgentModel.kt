package com.metrada.model

data class UpdateAgentModel(
    val host: String? = null,
    val port: Int? = null,
    val id: String? = null,
    val path: String? = null,
    val scrapeIntervalSeconds: Long? = null,
    val enabled: Boolean? = null,
    val timeoutSeconds: Int? = null,
)

