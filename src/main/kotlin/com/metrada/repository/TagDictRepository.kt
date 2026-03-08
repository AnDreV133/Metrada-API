package com.metrada.repository

import com.metrada.entity.TagDictEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TagDictRepository : JpaRepository<TagDictEntity, Long> {

    /**
     * Поиск по точной паре ключ-значение
     */
    @Query("SELECT t FROM TagDictEntity t WHERE t.tagKey = :key AND t.tagValue = :value")
    fun findByKeyAndValue(
        @Param("key") key: String,
        @Param("value") value: String
    ): TagDictEntity?

    /**
     * Поиск нескольких тегов по списку пар
     */
    @Query("""
        SELECT t FROM TagDictEntity t 
        WHERE (t.tagKey, t.tagValue) IN :pairs
    """)
    fun findAllByKeyValuePairs(
        @Param("pairs") pairs: Collection<Array<String>>
    ): List<TagDictEntity>

    /**
     * Поиск всех тегов с определенным ключом
     */
    @Query("SELECT t FROM TagDictEntity t WHERE t.tagKey = :key")
    fun findAllByKey(@Param("key") key: String): List<TagDictEntity>

    /**
     * Поиск по значению (для автодополнения)
     */
    @Query("""
        SELECT t FROM TagDictEntity t 
        WHERE t.tagKey = :key 
          AND t.tagValue LIKE %:valuePattern%
    """)
    fun findByKeyAndValueContaining(
        @Param("key") key: String,
        @Param("valuePattern") valuePattern: String
    ): List<TagDictEntity>

    /**
     * Получить все уникальные ключи тегов
     */
    @Query("SELECT DISTINCT t.tagKey FROM TagDictEntity t ORDER BY t.tagKey")
    fun findAllTagKeys(): List<String>

    /**
     * Получить все значения для конкретного ключа
     */
    @Query("SELECT t.tagValue FROM TagDictEntity t WHERE t.tagKey = :key ORDER BY t.tagValue")
    fun findAllValuesByKey(@Param("key") key: String): List<String>

    /**
     * Поиск или создание (для batch операций)
     * Внимание: этот метод нужно использовать с @Transactional
     */
    @Query("""
        SELECT t FROM TagDictEntity t 
        WHERE t.tagKey = :key AND t.tagValue = :value
    """)
    fun findOrCreate(
        @Param("key") key: String,
        @Param("value") value: String
    ): TagDictEntity?  // вернет null если не найден
}