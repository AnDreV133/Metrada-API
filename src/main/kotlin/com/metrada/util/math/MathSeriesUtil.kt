package com.metrada.util.math

import kotlin.math.*

/**
 * Утилитарный класс для математических операций над временными рядами
 * Реализует основные функции PromQL для работы с массивами Double
 */
object MathSeriesUtil {

    // ==================== 1. Агрегации по нескольким рядам ====================

    /**
     * sum() - сумма значений по всем рядам (поэлементно)
     * @param series - список массивов значений (все массивы должны быть одинаковой длины)
     */
    fun sum(series: List<List<Double>>): List<Double> {
        require(series.isNotEmpty()) { "Series list cannot be empty" }
        val size = series.first().size

        return (0 until size).map { i ->
            series.sumOf { it.getOrElse(i) { 0.0 } }
        }
    }

    /**
     * avg() - среднее значение по всем рядам
     */
    fun avg(series: List<List<Double>>): List<Double> {
        require(series.isNotEmpty()) { "Series list cannot be empty" }
        val size = series.first().size

        return (0 until size).map { i ->
            val valuesAtPoint = series.mapNotNull { it.getOrNull(i) }
            if (valuesAtPoint.isEmpty()) 0.0 else valuesAtPoint.average()
        }
    }

    /**
     * min() - минимальное значение по всем рядам
     */
    fun min(series: List<List<Double>>): List<Double> {
        require(series.isNotEmpty()) { "Series list cannot be empty" }
        val size = series.first().size

        return (0 until size).map { i ->
            series.minOfOrNull { it.getOrElse(i) { Double.POSITIVE_INFINITY } } ?: Double.NaN
        }
    }

    /**
     * max() - максимальное значение по всем рядам
     */
    fun max(series: List<List<Double>>): List<Double> {
        require(series.isNotEmpty()) { "Series list cannot be empty" }
        val size = series.first().size

        return (0 until size).map { i ->
            series.maxOfOrNull { it.getOrElse(i) { Double.NEGATIVE_INFINITY } } ?: Double.NaN
        }
    }

    /**
     * count() - количество рядов (одинаковое для всех точек)
     */
    fun count(series: List<List<Double>>): List<Double> {
        val size = series.first().size
        val count = series.size.toDouble()
        return List(size) { count }
    }

    /**
     * stddev() - стандартное отклонение
     */
    fun stddev(series: List<List<Double>>): List<Double> {
        require(series.isNotEmpty()) { "Series list cannot be empty" }
        val size = series.first().size

        return (0 until size).map { i ->
            val valuesAtPoint = series.mapNotNull { it.getOrNull(i) }
            if (valuesAtPoint.size < 2) 0.0
            else {
                val mean = valuesAtPoint.average()
                sqrt(valuesAtPoint.map { (it - mean).pow(2) }.average())
            }
        }
    }

    /**
     * topk() - возвращает k рядов с наибольшими значениями (по последней точке)
     */
    fun topk(k: Int, series: List<List<Double>>): List<List<Double>> {
        if (k <= 0 || series.isEmpty()) return emptyList()

        return series
            .sortedByDescending { it.lastOrNull() ?: Double.NEGATIVE_INFINITY }
            .take(k)
    }

    /**
     * bottomk() - возвращает k рядов с наименьшими значениями (по последней точке)
     */
    fun bottomk(k: Int, series: List<List<Double>>): List<List<Double>> {
        if (k <= 0 || series.isEmpty()) return emptyList()

        return series
            .sortedBy { it.lastOrNull() ?: Double.POSITIVE_INFINITY }
            .take(k)
    }

    /**
     * topk_per_point() - для каждого момента времени возвращает top k
     * Более точная эмуляция поведения PromQL
     */
    fun topkPerPoint(k: Int, series: List<List<Double>>): List<List<Double>> {
        if (k <= 0 || series.isEmpty()) return emptyList()

        val size = series.first().size
        val result = MutableList(series.size) { mutableListOf<Double>() }

        for (pointIdx in 0 until size) {
            val valuesAtPoint = series.mapIndexed { seriesIdx, values ->
                seriesIdx to values.getOrNull(pointIdx)
            }.filter { it.second != null }

            val topIndices = valuesAtPoint
                .sortedByDescending { it.second }
                .take(k)
                .map { it.first }

            for (topIdx in topIndices) {
                result[topIdx].add(series[topIdx][pointIdx])
            }

            for (seriesIdx in series.indices) {
                if (seriesIdx !in topIndices && result[seriesIdx].size <= pointIdx) {
                    result[seriesIdx].add(Double.NaN)
                }
            }
        }

        return result.filter { it.isNotEmpty() && it.any { !it.isNaN() } }
    }

    // ==================== 2. Rate-функции ====================

    /**
     * rate() - скорость роста за интервал (в секунду)
     * @param values - массив значений счетчика (монотонно возрастающий)
     * @param stepSeconds - интервал между точками в секундах
     * @param windowSeconds - размер окна для расчета (аналог [5m] в PromQL)
     */
    fun rate(values: List<Double>, stepSeconds: Long, windowSeconds: Long): List<Double> {
        if (values.size < 2) return emptyList()

        val windowPoints = (windowSeconds / stepSeconds).toInt().coerceAtLeast(1)
        val result = MutableList(values.size) { 0.0 }

        for (i in values.indices) {
            val startIndex = max(0, i - windowPoints)
            if (startIndex < i) {
                val timeDelta = (i - startIndex) * stepSeconds
                val valueDelta = values[i] - values[startIndex]

                if (timeDelta > 0 && valueDelta >= 0) {
                    result[i] = valueDelta / timeDelta
                }
            }
        }

        return result
    }

    /**
     * irate() - мгновенная скорость (по последним двум точкам)
     */
    fun irate(values: List<Double>, stepSeconds: Long): List<Double> {
        if (values.size < 2) return emptyList()

        val result = MutableList(values.size) { 0.0 }

        for (i in 1 until values.size) {
            val valueDelta = values[i] - values[i - 1]
            if (valueDelta >= 0) {
                result[i] = valueDelta / stepSeconds
            }
        }

        return result
    }

    /**
     * increase() - абсолютный прирост за интервал
     */
    fun increase(values: List<Double>, stepSeconds: Long, windowSeconds: Long): List<Double> {
        if (values.size < 2) return emptyList()

        val windowPoints = (windowSeconds / stepSeconds).toInt().coerceAtLeast(1)
        val result = MutableList(values.size) { 0.0 }

        for (i in values.indices) {
            val startIndex = max(0, i - windowPoints)
            if (startIndex < i) {
                val increase = values[i] - values[startIndex]
                result[i] = max(increase, 0.0)
            }
        }

        return result
    }

    /**
     * delta() - разница значений для gauge
     */
    fun delta(values: List<Double>, stepSeconds: Long, windowSeconds: Long): List<Double> {
        if (values.size < 2) return emptyList()

        val windowPoints = (windowSeconds / stepSeconds).toInt().coerceAtLeast(1)
        val result = MutableList(values.size) { 0.0 }

        for (i in values.indices) {
            val startIndex = max(0, i - windowPoints)
            if (startIndex < i) {
                result[i] = values[i] - values[startIndex]
            }
        }

        return result
    }

    // ==================== 3. _over_time функции ====================

    /**
     * avg_over_time() - среднее за окно
     */
    fun avgOverTime(values: List<Double>, stepSeconds: Long, windowSeconds: Long): List<Double> {
        return aggregateOverTime(values, stepSeconds, windowSeconds) { window ->
            window.average()
        }
    }

    /**
     * sum_over_time() - сумма за окно
     */
    fun sumOverTime(values: List<Double>, stepSeconds: Long, windowSeconds: Long): List<Double> {
        return aggregateOverTime(values, stepSeconds, windowSeconds) { window ->
            window.sum()
        }
    }

    /**
     * max_over_time() - максимум за окно
     */
    fun maxOverTime(values: List<Double>, stepSeconds: Long, windowSeconds: Long): List<Double> {
        return aggregateOverTime(values, stepSeconds, windowSeconds) { window ->
            window.maxOrNull() ?: Double.NaN
        }
    }

    /**
     * min_over_time() - минимум за окно
     */
    fun minOverTime(values: List<Double>, stepSeconds: Long, windowSeconds: Long): List<Double> {
        return aggregateOverTime(values, stepSeconds, windowSeconds) { window ->
            window.minOrNull() ?: Double.NaN
        }
    }

    /**
     * count_over_time() - количество точек за окно
     */
    fun countOverTime(values: List<Double>, stepSeconds: Long, windowSeconds: Long): List<Double> {
        return aggregateOverTime(values, stepSeconds, windowSeconds) { window ->
            window.size.toDouble()
        }
    }

    /**
     * quantile_over_time() - квантиль за окно
     */
    fun quantileOverTime(
        values: List<Double>,
        stepSeconds: Long,
        windowSeconds: Long,
        phi: Double,
    ): List<Double> {
        return aggregateOverTime(values, stepSeconds, windowSeconds) { window ->
            if (window.isEmpty()) Double.NaN
            else {
                val sorted = window.sorted()
                val index = (phi * (sorted.size - 1)).toInt()
                sorted[index]
            }
        }
    }

    /**
     * stddev_over_time() - стандартное отклонение за окно
     */
    fun stddevOverTime(values: List<Double>, stepSeconds: Long, windowSeconds: Long): List<Double> {
        return aggregateOverTime(values, stepSeconds, windowSeconds) { window ->
            if (window.size < 2) 0.0
            else {
                val mean = window.average()
                sqrt(window.map { (it - mean).pow(2) }.average())
            }
        }
    }

    // ==================== 4. Преобразования одного ряда ====================

    /**
     * abs() - модуль
     */
    fun abs(values: List<Double>): List<Double> = values.map { abs(it) }

    /**
     * floor() - округление вниз
     */
    fun floor(values: List<Double>): List<Double> = values.map { floor(it) }

    /**
     * ceil() - округление вверх
     */
    fun ceil(values: List<Double>): List<Double> = values.map { ceil(it) }

    /**
     * round() - округление до ближайшего целого
     */
    fun round(values: List<Double>): List<Double> = values.map { round(it) }

    /**
     * clamp_max() - ограничение сверху
     */
    fun clampMax(values: List<Double>, max: Double): List<Double> = values.map { min(it, max) }

    /**
     * clamp_min() - ограничение снизу
     */
    fun clampMin(values: List<Double>, min: Double): List<Double> = values.map { max(it, min) }

    /**
     * scalar() - последнее значение (скаляр)
     */
    fun scalar(values: List<Double>): Double = values.lastOrNull() ?: Double.NaN

    /**
     * vector() - преобразование скаляра в вектор (массив с одним значением)
     */
    fun vector(value: Double): List<Double> = listOf(value)

    // ==================== 5. Арифметические операции ====================

    /**
     * Сложение двух массивов (поэлементно)
     */
    fun add(a: List<Double>, b: List<Double>): List<Double> {
        val size = min(a.size, b.size)
        return (0 until size).map { a[it] + b[it] }
    }

    /**
     * Вычитание
     */
    fun subtract(a: List<Double>, b: List<Double>): List<Double> {
        val size = min(a.size, b.size)
        return (0 until size).map { a[it] - b[it] }
    }

    /**
     * Умножение
     */
    fun multiply(a: List<Double>, b: List<Double>): List<Double> {
        val size = min(a.size, b.size)
        return (0 until size).map { a[it] * b[it] }
    }

    /**
     * Деление
     */
    fun divide(a: List<Double>, b: List<Double>): List<Double> {
        val size = min(a.size, b.size)
        return (0 until size).map {
            if (b[it] != 0.0) a[it] / b[it] else Double.NaN
        }
    }

    /**
     * Умножение на скаляр
     */
    fun multiplyScalar(values: List<Double>, scalar: Double): List<Double> =
        values.map { it * scalar }

    /**
     * Прибавление скаляра
     */
    fun addScalar(values: List<Double>, scalar: Double): List<Double> =
        values.map { it + scalar }

    // ==================== 6. Логические операции ====================

    /**
     * > bool - сравнение с порогом (возвращает 1 или 0)
     */
    fun greaterThan(values: List<Double>, threshold: Double): List<Double> =
        values.map { if (it > threshold) 1.0 else 0.0 }

    /**
     * < bool - сравнение с порогом
     */
    fun lessThan(values: List<Double>, threshold: Double): List<Double> =
        values.map { if (it < threshold) 1.0 else 0.0 }

    /**
     * >= bool - сравнение с порогом
     */
    fun greaterOrEqual(values: List<Double>, threshold: Double): List<Double> =
        values.map { if (it >= threshold) 1.0 else 0.0 }

    /**
     * <= bool - сравнение с порогом
     */
    fun lessOrEqual(values: List<Double>, threshold: Double): List<Double> =
        values.map { if (it <= threshold) 1.0 else 0.0 }

    /**
     * == bool - сравнение с порогом
     */
    fun equal(values: List<Double>, threshold: Double): List<Double> =
        values.map { if (it == threshold) 1.0 else 0.0 }

    // ==================== 7. Хистограммы ====================

    /**
     * histogram_quantile() - аппроксимация квантиля из гистограммы
     * @param phi - квантиль (0.5, 0.9, 0.95, 0.99)
     * @param buckets - список пар (верхняя граница, значение счетчика)
     */
    fun histogramQuantile(phi: Double, buckets: List<Pair<Double, Double>>): Double {
        val sorted = buckets.sortedBy { it.first }
        if (sorted.isEmpty()) return Double.NaN

        val total = sorted.last().second
        val target = phi * total

        var cumulative = 0.0
        for (i in sorted.indices) {
            val (le, count) = sorted[i]
            cumulative = count

            if (cumulative >= target) {
                if (i == 0) return le / 2

                val (prevLe, prevCount) = sorted[i - 1]
                if (count == prevCount) return le

                val fraction = (target - prevCount) / (count - prevCount)
                return prevLe + (le - prevLe) * fraction
            }
        }

        return sorted.last().first
    }

    // ==================== Вспомогательные методы ====================

    private fun aggregateOverTime(
        values: List<Double>,
        stepSeconds: Long,
        windowSeconds: Long,
        reducer: (List<Double>) -> Double,
    ): List<Double> {
        if (values.isEmpty()) return emptyList()

        val windowPoints = (windowSeconds / stepSeconds).toInt().coerceAtLeast(1)
        val result = MutableList(values.size) { 0.0 }

        for (i in values.indices) {
            val startIndex = max(0, i - windowPoints + 1)
            val window = values.subList(startIndex, i + 1)
            result[i] = reducer(window)
        }

        return result
    }
}