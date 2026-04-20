package com.metrada.model

import java.time.Instant

data class TimestampValueModel(
    val timestamp: Instant,
    val value: Double,
)