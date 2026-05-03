package com.metrada.entity

import jakarta.persistence.*

@Entity
@Table(name = "labels_dict")
data class LabelDictEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "label_key", nullable = false)
    val labelKey: String,

    @Column(name = "label_value", nullable = false)
    val labelValue: String,

    @Column(unique = true, nullable = false)
    val hash: String,
) {
    companion object {
        fun fromKeyValue(key: String, value: String): LabelDictEntity {
            return LabelDictEntity(
                labelKey = key,
                labelValue = value,
                hash = generateHash(key, value)
            )
        }

        fun generateHash(key: String, value: String): String {
            return "$key=$value".hashCode().toString(16)
        }
    }
}