package com.metrada.model

import java.time.Instant
import java.util.*

data class MetricSampleModel(
    val name: String,
    val value: Double,
    val timestamp: Instant? = null, // если null, используем время сервера
    val tags: Map<String, String> = Collections.emptyMap()
)