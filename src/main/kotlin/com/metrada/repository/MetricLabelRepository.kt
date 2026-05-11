package com.metrada.repository

import com.metrada.entity.relation.MetricLabelEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
interface MetricLabelRepository : JpaRepository<MetricLabelEntity, Long> {
    @Query(
        """
    SELECT DISTINCT m.hash
    FROM MetricEntity m
    WHERE m.id IN (
        SELECT ml.metric.id
        FROM MetricLabelEntity ml
        WHERE ml.labelDict.hash IN :labelHashes
        GROUP BY ml.metric.id
        HAVING COUNT(DISTINCT ml.labelDict.id) = :requiredCount
    )
    """
    )
    fun findMetricHashesByRequiredLabelHashes(
        @Param("labelHashes") labelHashes: List<String>,
        @Param("requiredCount") requiredCount: Int,
    ): List<Int>

    /**
     * Находит ID метрик, которые содержат все указанные хэши лейблов.
     * @param hashes список хэшей лейблов (обязательных)
     * @param requiredCount количество хэшей (должно быть равно размеру hashes)
     * @return список ID метрик
     */
    @Query(
        """
        SELECT ml.metric.id
        FROM MetricLabelEntity ml
        WHERE ml.labelDict.hash IN :hashes
        GROUP BY ml.metric.id
        HAVING COUNT(DISTINCT ml.labelDict.id) = :requiredCount
        """
    )
    fun findMetricIdsByHashes(
        @Param("hashes") hashes: List<String>,
        @Param("requiredCount") requiredCount: Int,
    ): List<Long>


    /**
     * Удаление orphaned тегов (теги, которые не связаны ни с одной метрикой)
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM LabelDictEntity td WHERE NOT EXISTS (SELECT 1 FROM MetricLabelEntity ml WHERE ml.labelDict = td)")
    fun deleteAllOrphanedTags(): Int

//    @Modifying
//    @Transactional
//    @Query("DELETE FROM MetricTagEntity mt WHERE mt.metric.id = :metricId")
//    fun deleteByMetricId(@Param("metricId") metricId: Long)
//
//    @Query("SELECT mt FROM MetricTagEntity mt WHERE mt.metric.id = :metricId")
//    fun findByMetricId(@Param("metricId") metricId: Long): List<MetricLabelEntity>
//
//    @Query(
//        """
//        SELECT td.tagValue, COUNT(mt)
//        FROM MetricTagEntity mt
//        JOIN mt.tagDict td
//        WHERE td.tagKey = :tagKey
//          AND mt.metric.timestamp BETWEEN :start AND :end
//        GROUP BY td.tagValue
//    """
//    )
//    fun getTagValueDistribution(
//        @Param("tagKey") tagKey: String,
//        @Param("start") start: Instant,
//        @Param("end") end: Instant,
//    ): List<Array<Any>>
}