package com.metrada.entity.relation

import com.metrada.entity.MetricEntity
import com.metrada.entity.LabelDictEntity
import jakarta.persistence.*

@Entity
@Table(name = "metrics_and_labels")
data class MetricLabelEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "metric_id", nullable = false)
    val metric: MetricEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "label_dict_id", nullable = false)
    val labelDict: LabelDictEntity,
)