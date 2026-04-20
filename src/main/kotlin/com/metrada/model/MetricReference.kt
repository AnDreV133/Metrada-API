package com.metrada.model

data class MetricReference(
    val name: String,
    val tags: Map<String, String>,
    val rangeSeconds: Long? = null,
) {
    fun toKey(): String = buildString {
        append(name)
        if (tags.isNotEmpty()) {
            append("{")
            append(tags.entries.joinToString(",") { "${it.key}=${it.value}" })
            append("}")
        }
        if (rangeSeconds != null) {
            append("[${formatDuration(rangeSeconds)}]")
        }
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds % 86400 == 0L -> "${seconds / 86400}d"
        seconds % 3600 == 0L -> "${seconds / 3600}h"
        seconds % 60 == 0L -> "${seconds / 60}m"
        else -> "${seconds}s"
    }
}