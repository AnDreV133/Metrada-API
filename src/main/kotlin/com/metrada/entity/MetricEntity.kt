package com.metrada.entity

import com.metrada.entity.relation.MetricTagEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "metrics")
data class MetricEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val value: Double,

    @Column(nullable = false)
    val timestamp: Instant,

    @Column(name = "agent_id", length = 50, nullable = false)
    val agentId: String,

    @OneToMany(mappedBy = "metric", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val metricTags: List<MetricTagEntity> = emptyList()
)