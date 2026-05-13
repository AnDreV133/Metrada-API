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

    @Column(nullable = false)
    val enabled: Boolean = true,

    @Column(name = "created_at", updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
)