package com.metrada.model

import java.time.Instant
import java.util.*

data class MetricSampleModel(
    val value: Double,
    val timestamp: Instant? = null, // если null, используем время сервера
    val labels: Map<String, String> = Collections.emptyMap(),
    val hash: Int
)