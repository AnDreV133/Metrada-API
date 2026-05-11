package com.metrada.repository

import com.metrada.entity.LabelDictEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface LabelDictRepository : JpaRepository<LabelDictEntity, Long> {
    /**
     * Поиск по точной паре ключ-значение
     */
    @Query("SELECT t FROM LabelDictEntity t WHERE t.labelKey = :key AND t.labelValue = :value")
    fun findByKeyAndValue(
        @Param("key") key: String,
        @Param("value") value: String
    ): LabelDictEntity?

    fun findByHash(hash: Int): LabelDictEntity
    @Query("SELECT ld FROM LabelDictEntity ld WHERE ld.hash IN :hashes")
    fun findAllByHashIn(@Param("hashes") hashes: List<Int>): List<LabelDictEntity>

}