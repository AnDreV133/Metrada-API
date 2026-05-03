package com.metrada.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "tags_dict")
data class TagDictEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "tag_key", nullable = false)
    val tagKey: String,

    @Column(name = "tag_value", nullable = false)
    val tagValue: String,

    @Column(unique = true, nullable = false)
    val hash: String,
) {
    companion object {
        fun fromKeyValue(key: String, value: String): TagDictEntity {
            return TagDictEntity(
                tagKey = key,
                tagValue = value,
                hash = generateHash(key, value)
            )
        }

        fun generateHash(key: String, value: String): String {
            return "$key=$value".hashCode().toString(16)
        }
    }
}