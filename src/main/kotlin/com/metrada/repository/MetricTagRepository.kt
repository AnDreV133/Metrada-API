package com.metrada.repository

import com.metrada.entity.relation.MetricTagEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
interface MetricTagRepository : JpaRepository<MetricTagEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM MetricTagEntity mt WHERE mt.metric.id = :metricId")
    fun deleteByMetricId(@Param("metricId") metricId: Long)

    @Query("SELECT mt FROM MetricTagEntity mt WHERE mt.metric.id = :metricId")
    fun findByMetricId(@Param("metricId") metricId: Long): List<MetricTagEntity>

    @Query(
        """
        SELECT td.tagValue, COUNT(mt)
        FROM MetricTagEntity mt
        JOIN mt.tagDict td
        WHERE td.tagKey = :tagKey
          AND mt.metric.timestamp BETWEEN :start AND :end
        GROUP BY td.tagValue
    """
    )
    fun getTagValueDistribution(
        @Param("tagKey") tagKey: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<Array<Any>>
}