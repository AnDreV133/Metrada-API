package com.metrada.entity.relation

import com.metrada.entity.MetricEntity
import com.metrada.entity.TagDictEntity
import jakarta.persistence.*

@Entity
@Table(name = "metric_tags")
data class MetricTagEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "metric_id", nullable = false)
    val metric: MetricEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_dict_id", nullable = false)
    val tagDict: TagDictEntity,
)