// common/types.kt
package com.metrada.service.parser

// Позиция в исходном запросе
data class PosRange(val start: Int, val end: Int) {
    companion object {
        val EMPTY = PosRange(0, 0)
    }
}


// Метки (labels) – упрощённое хранилище пар ключ-значение
//data class Labels(val values: Map<String, String>) {
//    fun get(name: String): String? = values[name]
//    fun with(key: String, value: String) = Labels(values + (key to value))
//    fun without(vararg keys: String) = Labels(values.filterKeys { it !in keys })
//    fun keep(vararg keys: String) = Labels(values.filterKeys { it in keys })
//    fun hash(): Long = values.entries.fold(0L) { acc, (k, v) -> acc xor (k.hashCode() to v.hashCode()).hashCode().toLong() }
//    override fun toString() = values.entries.joinToString(", ") { "${it.key}=${it.value}" }
//}

// Точка (значение в момент времени) – только float
data class Sample(val labels: Labels, val t: Long, val f: Double, val dropName: Boolean = false)

// Серия – список точек (для матрицы)
data class Series(val labels: Labels, val points: MutableList<FPoint>, val dropName: Boolean = false)
data class FPoint(val timestamp: Long, val value: Double)

// Результат выполнения запроса
sealed class Value {
    data class Scalar(val timestamp: Long, val value: Double) : Value()
    data class Vector(val samples: List<Sample>) : Value()
    data class Matrix(val series: List<Series>) : Value()
    data class String(val timestamp: Long, val value: kotlin.String) : Value()
}

// Фильтр по меткам (из парсера)
enum class MatchTypeEnum { Equal, NotEqual, Regexp, NotRegexp }
data class LabelMatcher(val type: MatchTypeEnum, val name: String, val value: String) {
    fun matches(labelValue: String): Boolean = when (type) {
        MatchTypeEnum.Equal -> labelValue == value
        MatchTypeEnum.NotEqual -> labelValue != value
        MatchTypeEnum.Regexp -> labelValue.contains(value.toRegex())
        MatchTypeEnum.NotRegexp -> !labelValue.contains(value.toRegex())
    }
}

enum class ValueTypeEnum {
    None, Scalar, Vector, Matrix, String
}

data class Labels(val values: Map<String, String>) {
    fun get(name: String): String? = values[name]
    fun has(name: String): Boolean = values.containsKey(name)
    fun with(name: String, value: String) = Labels(values + (name to value))
    fun without(vararg names: String): Labels {
        val newMap = values.toMutableMap()
        names.forEach { newMap.remove(it) }
        return Labels(newMap)
    }

    fun keep(vararg names: String): Labels {
        val newMap = values.filterKeys { it in names }.toMutableMap()
        return Labels(newMap)
    }

    fun hash(): Long = values.entries.fold(0L) { acc, e -> acc xor e.hashCode().toLong() }
    fun isEmpty(): Boolean = values.isEmpty()
    override fun toString(): String = values.entries.joinToString(", ") { "${it.key}=${it.value}" }
}