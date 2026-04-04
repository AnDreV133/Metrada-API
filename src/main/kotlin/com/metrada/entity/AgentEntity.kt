package com.metrada.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "agents")
data class AgentEntity(
    @Id
    @Column(length = 50, nullable = false)
    val id: String,  // ручной ввод ID

    @Column(nullable = false)
    val host: String,

    @Column(nullable = false)
    val port: Int,

    @Column(nullable = false)
    val path: String = "/metrics",

    @Column(name = "scrape_interval_seconds", nullable = false)
    val scrapeIntervalSeconds: Long = 15,

    @Column(name = "retention_days")
    val retentionDays: Int = 30,

    @Column(nullable = false)
    val enabled: Boolean = true,

    @Column(name = "timeout_seconds")
    val timeoutSeconds: Int = 5,

    @Column(name = "last_scrape_at")
    var lastScrapeAt: Instant? = null,

    @Column(name = "last_scrape_status")
    var lastScrapeStatus: String? = null, // "success", "failed"

    @Column(name = "created_at", updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
) {
    fun getRetentionThreshold(): Instant = Instant.now().minusSeconds(retentionDays * 24L * 60 * 60)
}