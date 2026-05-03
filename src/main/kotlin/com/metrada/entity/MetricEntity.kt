package com.metrada.entity

import com.metrada.entity.relation.MetricLabelEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "metrics",
    indexes = [Index(columnList = "hash")]
)
data class MetricEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val value: Double,

    @Column(nullable = false)
    val timestamp: Instant,

    @Column(name = "agent_id", length = 50, nullable = false)
    val agentId: String,

    @Column(name = "hash", nullable = false)
    val hash: Int,

    @OneToMany(mappedBy = "metric", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val metricLabels: List<MetricLabelEntity> = emptyList(),
)