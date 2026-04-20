@file:JvmName("SimpleExpressionParserTestKt")

package com.metrada

import com.metrada.util.math.ExpressionParser
import com.metrada.util.math.MathSeriesUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class ComplexExpressionParserTest {

    // ==================== 1. Сложные вложенные выражения ====================

    @Test
    fun `should calculate CPU usage with multiple nested functions`() {
        // given - idle проценты (уменьшаются, CPU загружается)
        val idleValues = listOf(95.0, 90.0, 85.0, 80.0, 75.0, 70.0, 65.0, 60.0)
        
        // when - формула CPU usage из Node Exporter дашборда
        // clamp_min(multiply_scalar(subtract(vector(1), avg(rate(idle, 15, 60))), 100), 0)
        val result = ExpressionParser.parse(
            "clamp_min(multiply_scalar(subtract(vector(1), avg(rate(idle, 15, 60))), 100), 0)",
            mapOf("idle" to idleValues)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val cpuBusy = result as List<Double>
        
        assertEquals(idleValues.size, cpuBusy.size)
        // Все значения должны быть в диапазоне 0-100
        assertTrue(cpuBusy.all { it in 0.0..100.0 })
        // CPU usage должен расти (idle падает)
        assertTrue(cpuBusy.last() > cpuBusy.first())
    }

    @Test
    fun `should calculate average of multiple rates`() {
        // given
        val counter1 = listOf(100.0, 120.0, 140.0, 160.0, 180.0, 200.0)
        val counter2 = listOf(50.0, 70.0, 90.0, 110.0, 130.0, 150.0)
        val counter3 = listOf(200.0, 210.0, 220.0, 230.0, 240.0, 250.0)
        
        // when
        val result = ExpressionParser.parse(
            "avg(rate(c1, 15, 60), rate(c2, 15, 60), rate(c3, 15, 60))",
            mapOf("c1" to counter1, "c2" to counter2, "c3" to counter3)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val avgRates = result as List<Double>
        
        assertEquals(counter1.size, avgRates.size)
        // rate(c1) = (200-100)/60 = 1.667, rate(c2) = (150-50)/60 = 1.667, rate(c3) = (250-200)/60 = 0.833
        // average = (1.667 + 1.667 + 0.833) / 3 = 1.389
        assertEquals(0.0, avgRates[0])
        assertEquals(0.0, avgRates[1])
        assertEquals(0.0, avgRates[2])
        assertEquals(0.0, avgRates[3])
        assertEquals(1.389, avgRates[4], 0.01)
        assertEquals(1.389, avgRates[5], 0.01)
    }

    @Test
    fun `should calculate sum of topk results`() {
        // given - 5 рядов с разными значениями
        val series = listOf(
            listOf(1.0, 2.0, 3.0, 4.0, 5.0),    // сумма = 15
            listOf(10.0, 20.0, 30.0, 40.0, 50.0), // сумма = 150
            listOf(5.0, 6.0, 7.0, 8.0, 9.0),     // сумма = 35
            listOf(100.0, 90.0, 80.0, 70.0, 60.0), // сумма = 400
            listOf(2.0, 4.0, 6.0, 8.0, 10.0)      // сумма = 30
        )
        
        val seriesMap = mapOf(
            "s1" to series[0], "s2" to series[1], "s3" to series[2],
            "s4" to series[3], "s5" to series[4]
        )
        
        // when - взять топ-3 по последнему значению и просуммировать
        val result = ExpressionParser.parse(
            "sum(topk(3, s1, s2, s3, s4, s5))",
            seriesMap
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val sumResult = result as List<Double>
        
        // Топ-3 по последнему значению: s4(60), s2(50), s5(10)
        // Поэлементная сумма: s4+s2+s5
        assertEquals(5, sumResult.size)
        assertEquals(100.0 + 10.0 + 2.0, sumResult[0])
        assertEquals(90.0 + 20.0 + 4.0, sumResult[1])
        assertEquals(80.0 + 30.0 + 6.0, sumResult[2])
        assertEquals(70.0 + 40.0 + 8.0, sumResult[3])
        assertEquals(60.0 + 50.0 + 10.0, sumResult[4])
    }

    @Test
    fun `should calculate quantile from histogram with nested sum by le`() {
        // given - гистограммные данные для нескольких временных точек
        val leValues = listOf("0.1", "0.2", "0.5", "1.0", "+Inf")
        
        // Моделируем данные за 3 временных точки
        // t0: counts = [10, 25, 45, 70, 100]
        // t1: counts = [15, 30, 50, 75, 100]
        // t2: counts = [20, 35, 55, 80, 100]
        
        val bucketsAtT0 = leValues.mapIndexed { i, le -> 
            createMetricForHistogram("requests", le, listOf(10.0 + i*15, 25.0 + i*10, 45.0 + i*5, 70.0, 100.0)[i]) 
        }
        
        // when - вычисляем 95-й перцентиль для каждого момента
        // В реальном запросе было бы: histogram_quantile(0.95, sum by (le) (rate(...)))
        // Для простоты тестируем напрямую функцию
        
        val p95atT0 = MathSeriesUtil.histogramQuantile(0.95, listOf(
            0.1 to 10.0, 0.2 to 25.0, 0.5 to 45.0, 1.0 to 70.0, Double.POSITIVE_INFINITY to 100.0
        ))
        
        val p95atT1 = MathSeriesUtil.histogramQuantile(0.95, listOf(
            0.1 to 15.0, 0.2 to 30.0, 0.5 to 50.0, 1.0 to 75.0, Double.POSITIVE_INFINITY to 100.0
        ))
        
        val p95atT2 = MathSeriesUtil.histogramQuantile(0.95, listOf(
            0.1 to 20.0, 0.2 to 35.0, 0.5 to 55.0, 1.0 to 80.0, Double.POSITIVE_INFINITY to 100.0
        ))
        
        // then
        assertTrue(p95atT0 in 0.5..1.0)
        assertTrue(p95atT1 in 0.5..1.0)
        assertTrue(p95atT2 in 0.5..1.0)
        // С ростом нагрузки перцентиль должен увеличиваться
        assertTrue(p95atT2 >= p95atT0)
    }

    @Test
    fun `should calculate prediction using multiple functions`() {
        // given - растущие данные
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)
        
        // when - сложное выражение с rate, avg, clamp
        val result = ExpressionParser.parse(
            "clamp_max(round(multiply_scalar(avg(rate(data, 15, 60)), 100)), 100)",
            mapOf("data" to values)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val predicted = result as List<Double>
        
        // Все значения должны быть целыми (после round) и не больше 100
        assertTrue(predicted.all { it == kotlin.math.round(it) })
        assertTrue(predicted.all { it <= 100.0 })
    }

    // ==================== 2. Арифметика с несколькими операциями ====================

    @Test
    fun `should calculate percentage using arithmetic chain`() {
        // given
        val used = listOf(30.0, 40.0, 50.0, 60.0, 70.0)
        val total = listOf(100.0, 100.0, 100.0, 100.0, 100.0)
        
        // when - (used / total) * 100
        val result = ExpressionParser.parse(
            "multiply_scalar(divide(used, total), 100)",
            mapOf("used" to used, "total" to total)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val percentage = result as List<Double>
        
        assertEquals(listOf(30.0, 40.0, 50.0, 60.0, 70.0), percentage)
    }

    @Test
    fun `should calculate complex expression with multiple arithmetic operations`() {
        // given
        val a = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val b = listOf(2.0, 3.0, 4.0, 5.0, 6.0)
        val c = listOf(5.0, 10.0, 15.0, 20.0, 25.0)
        
        // when - ((a + b) * c) / 2
        val result = ExpressionParser.parse(
            "divide(multiply(add(a, b), c), vector(2))",
            mapOf("a" to a, "b" to b, "c" to c)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val computed = result as List<Double>
        
        for (i in a.indices) {
            val expected = ((a[i] + b[i]) * c[i]) / 2
            assertEquals(expected, computed[i], 0.001)
        }
    }

    // ==================== 3. Краевые случаи с разными длинами рядов ====================

    @Test
    fun `should handle series of different lengths`() {
        // given
        val longSeries = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
        val shortSeries = listOf(100.0, 200.0, 300.0, 400.0, 500.0)
        
        // when - сложение обрезается по минимальной длине
        val result = ExpressionParser.parse(
            "add(long, short)",
            mapOf("long" to longSeries, "short" to shortSeries)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val added = result as List<Double>
        
        assertEquals(shortSeries.size, added.size)
        for (i in shortSeries.indices) {
            assertEquals(longSeries[i] + shortSeries[i], added[i], 0.001)
        }
    }

    @Test
    fun `should handle empty series in complex expression`() {
        // given
        val emptySeries = emptyList<Double>()
        val normalSeries = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        
        // when
        val result = ExpressionParser.parse(
            "add(empty, normal)",
            mapOf("empty" to emptySeries, "normal" to normalSeries)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val added = result as List<Double>
        
        assertEquals(normalSeries.size, added.size)
        assertEquals(normalSeries, added)
    }

    // ==================== 4. Вложенность более 3 уровней ====================

    @Test
    fun `should handle 4-level nested functions`() {
        // given
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0)
        
        // when - round(clamp_min(multiply_scalar(avg(rate(data,15,60)),100),0))
        val result = ExpressionParser.parse(
            "round(clamp_min(multiply_scalar(avg(rate(data, 15, 60)), 100), 0))",
            mapOf("data" to values)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val computed = result as List<Double>
        
        assertEquals(values.size, computed.size)
        // Все значения должны быть целыми
        assertTrue(computed.all { it == kotlin.math.round(it) })
    }

    @Test
    fun `should handle 5-level nested functions`() {
        // given
        val values = listOf(100.0, 200.0, 300.0, 400.0, 500.0)
        
        // when - clamp_max(round(multiply_scalar(subtract(vector(1), avg(rate(data,15,60))), 100)), 99)
        val result = ExpressionParser.parse(
            "clamp_max(round(multiply_scalar(subtract(vector(1), avg(rate(data, 15, 60))), 100)), 99)",
            mapOf("data" to values)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val computed = result as List<Double>
        
        // Все значения должны быть не больше 99
        assertTrue(computed.all { it <= 99.0 })
    }

    // ==================== 5. Комбинация rate и _over_time ====================

    @Test
    fun `should calculate rate then average over time`() {
        // given
        val counter = listOf(
            100.0, 110.0, 120.0, 130.0, 140.0, 150.0,
            160.0, 170.0, 180.0, 190.0, 200.0, 210.0
        )
        
        // when - avg_over_time(rate(data,15,60), 15, 120)
        // Сначала rate за 60 секунд, потом среднее за 120 секунд
        val result = ExpressionParser.parse(
            "avg_over_time(rate(data, 15, 60), 15, 120)",
            mapOf("data" to counter)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val smoothed = result as List<Double>
        
        assertEquals(counter.size, smoothed.size)
        // Проверяем, что значения сглажены
        assertTrue(smoothed.windowed(2).all { (a, b) -> abs(a - b) <= 0.2 })
    }

    @Test
    fun `should calculate max_over_time of rate`() {
        // given
        val counter = listOf(
            100.0, 120.0, 110.0, 130.0, 125.0, 140.0,
            135.0, 150.0, 145.0, 160.0, 155.0, 170.0
        )
        
        // when - max_over_time(rate(data,15,60), 15, 120)
        val result = ExpressionParser.parse(
            "max_over_time(rate(data, 15, 60), 15, 120)",
            mapOf("data" to counter)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val maxRates = result as List<Double>
        
        assertEquals(counter.size, maxRates.size)
        // Максимум должен быть не меньше обычного rate
        val plainRates = MathSeriesUtil.rate(counter, 15, 60)
        for (i in maxRates.indices) {
            assertTrue(maxRates[i] >= plainRates[i])
        }
    }

    // ==================== 6. Сравнения и пороги ====================

    @Test
    fun `should calculate binary threshold with multiple conditions`() {
        // given
        val values = listOf(5.0, 15.0, 25.0, 35.0, 45.0, 55.0)
        
        // when - greater_than(values, 30)
        val result = ExpressionParser.parse(
            "greater_than(data, 30)",
            mapOf("data" to values)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val thresholded = result as List<Double>
        
        assertEquals(listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), thresholded)
    }

    @Test
    fun `should combine threshold with arithmetic`() {
        // given
        val cpuUsage = listOf(10.0, 30.0, 50.0, 70.0, 90.0)
        
        // when - multiply_scalar(greater_than(cpu, 80), 100) - 100% если >80%, иначе 0%
        val result = ExpressionParser.parse(
            "multiply_scalar(greater_than(cpu, 80), 100)",
            mapOf("cpu" to cpuUsage)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val alert = result as List<Double>
        
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 100.0), alert)
    }

    // ==================== 7. Топологические операции ====================

    @Test
    fun `should calculate topk then average`() {
        // given - 6 рядов
        val seriesList = listOf(
            listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),      // last=6
            listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0), // last=60
            listOf(5.0, 6.0, 7.0, 8.0, 9.0, 10.0),      // last=10
            listOf(100.0, 90.0, 80.0, 70.0, 60.0, 50.0), // last=50
            listOf(2.0, 4.0, 6.0, 8.0, 10.0, 12.0),     // last=12
            listOf(20.0, 30.0, 40.0, 50.0, 60.0, 70.0)   // last=70
        )
        
        val seriesMap = mapOf(
            "s1" to seriesList[0], "s2" to seriesList[1], "s3" to seriesList[2],
            "s4" to seriesList[3], "s5" to seriesList[4], "s6" to seriesList[5]
        )
        
        // when - avg(topk(3, s1, s2, s3, s4, s5, s6))
        val result = ExpressionParser.parse(
            "avg(topk(3, s1, s2, s3, s4, s5, s6))",
            seriesMap
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val avgTop3 = result as List<Double>
        
        assertEquals(6, avgTop3.size)
        // Топ-3 по последнему значению: s6(70), s2(60), s4(50)
        // Поэлементное среднее
        for (i in 0..5) {
            val expected = (seriesList[5][i] + seriesList[1][i] + seriesList[3][i]) / 3.0
            assertEquals(expected, avgTop3[i], 0.001)
        }
    }

    // ==================== 8. Обработка ошибок ====================

    @Test
    fun `should throw exception for unknown metric name`() {
        assertThrows<IllegalStateException> {
            ExpressionParser.parse(
                "sum(unknown_metric)",
                mapOf("known" to listOf(1.0, 2.0, 3.0))
            )
        }
    }

    @Test
    fun `should throw exception for malformed function call`() {
        assertThrows<IllegalStateException> {
            ExpressionParser.parse(
                "sum(data",
                mapOf("data" to listOf(1.0, 2.0, 3.0))
            )
        }
    }

    @Test
    fun `should throw exception for unknown function`() {
        assertThrows<IllegalStateException> {
            ExpressionParser.parse(
                "unknown_function(data)",
                mapOf("data" to listOf(1.0, 2.0, 3.0))
            )
        }
    }

    @Test
    fun `should throw exception for mismatched parentheses`() {
        assertThrows<IllegalStateException> {
            ExpressionParser.parse(
                "sum(data, (avg(other))",
                mapOf("data" to listOf(1.0), "other" to listOf(2.0))
            )
        }
    }

    // ==================== 9. Производительность больших выражений ====================

    @Test
    fun `should handle very long chain of operations`() {
        // given
        val values = (1..100).map { it.toDouble() }
        
        // when - цепочка из 10 операций
        val result = ExpressionParser.parse(
            "round(ceil(floor(abs(clamp_min(clamp_max(multiply_scalar(data, 2), 100), 0)))))",
            mapOf("data" to values)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val computed = result as List<Double>
        
        assertEquals(values.size, computed.size)
        assertTrue(computed.all { it in 0.0..100.0 })
    }

    // ==================== 10. Реальные сценарии из мониторинга ====================

    @Test
    fun `should calculate memory usage percentage`() {
        // given
        val memTotal = listOf(16000000000.0, 16000000000.0, 16000000000.0) // 16GB
        val memAvailable = listOf(8000000000.0, 6000000000.0, 4000000000.0) // уменьшается
        
        // when - (1 - available/total) * 100
        val result = ExpressionParser.parse(
            "clamp_min(multiply_scalar(subtract(vector(1), divide(available, total)), 100), 0)",
            mapOf("available" to memAvailable, "total" to memTotal)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val memUsedPercent = result as List<Double>
        
        assertEquals(50.0, memUsedPercent[0], 0.01)
        assertEquals(62.5, memUsedPercent[1], 0.01)
        assertEquals(75.0, memUsedPercent[2], 0.01)
    }

    @Test
    fun `should calculate disk space usage with alert threshold`() {
        // given
        val diskTotal = listOf(100.0, 100.0, 100.0, 100.0, 100.0)
        val diskFree = listOf(80.0, 60.0, 40.0, 20.0, 10.0)
        
        // when - проверка на превышение 80% использования
        val result = ExpressionParser.parse(
            "greater_than(multiply_scalar(divide(free, total), 100), 80)",
            mapOf("free" to diskFree, "total" to diskTotal)
        )
        
        // then
        assertTrue(result is List<*>)
        @Suppress("UNCHECKED_CAST")
        val alert = result as List<Double>
        
        // free: 80%, 60%, 40%, 20%, 10% → used: 20%, 40%, 60%, 80%, 90%
        // >80% used → только последняя точка
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 1.0), alert)
    }
}

// Вспомогательная функция для создания тестовых метрик гистограммы
private fun createMetricForHistogram(name: String, le: String, value: Double): Triple<String, String, Double> {
    return Triple(name, le, value)
}