package com.metrada

import com.metrada.util.math.ExpressionParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Тесты для парсера PromQL-подобных запросов
 */
class SimpleExpressionParserTest {

    private lateinit var seriesMap: Map<String, List<Double>>

    @BeforeEach
    fun setUp() {
        seriesMap = mapOf(
            "cpu0" to listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0),
            "cpu1" to listOf(8.0, 10.0, 12.0, 14.0, 16.0, 18.0),
            "cpu2" to listOf(12.0, 14.0, 16.0, 18.0, 20.0, 22.0)
        )
    }

    @Test
    fun `should parse sum query`() {
        val result = ExpressionParser.parse("sum(cpu0, cpu1, cpu2)", seriesMap) as List<Double>

        val expected = listOf(30.0, 36.0, 42.0, 48.0, 54.0, 60.0)
        assertEquals(expected, result)
    }

    @Test
    fun `should parse avg query`() {
        val result = ExpressionParser.parse("avg(cpu0, cpu1, cpu2)", seriesMap) as List<Double>

        val expected = listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0)
        assertEquals(expected, result)
    }

    @Test
    fun `should parse rate query`() {
        val result = ExpressionParser.parse("rate(cpu0, 15, 60)", seriesMap) as List<Double>

        assertEquals(6, result.size)
        assertTrue(result[0] == 0.0)
    }

    @Test
    fun `should parse topk query`() {
        val result = ExpressionParser.parse("topk(2, cpu0, cpu1, cpu2)", seriesMap) as List<List<Double>>

        assertEquals(2, result.size)
        assertEquals(seriesMap["cpu2"], result[0])  // max last value (22)
        assertEquals(seriesMap["cpu0"], result[1])  // second max (20)
    }

    @Test
    fun `should parse clamp_min query`() {
        val data = mapOf("values" to listOf(-5.0, -3.0, 0.0, 2.0, 4.0))
        val result = ExpressionParser.parse("clamp_min(values, 0)", data) as List<Double>

        val expected = listOf(0.0, 0.0, 0.0, 2.0, 4.0)
        assertEquals(expected, result)
    }

    @Test
    fun `should parse query with spaces`() {
        val result = ExpressionParser.parse("sum( cpu0 , cpu1 , cpu2 )", seriesMap) as List<Double>

        val expected = listOf(30.0, 36.0, 42.0, 48.0, 54.0, 60.0)
        assertEquals(expected, result)
    }

    @Test
    fun `should throw exception for unknown function`() {
        assertThrows<IllegalStateException> {
            ExpressionParser.parse("unknown(cpu0)", seriesMap)
        }
    }

    @Test
    fun `should throw exception for unknown series`() {
        assertThrows<IllegalStateException> {
            ExpressionParser.parse("sum(unknown_series)", seriesMap)
        }
    }

    @Test
    fun `should throw exception for malformed query`() {
        assertThrows<IllegalStateException> {
            ExpressionParser.parse("sum(cpu0, cpu1", seriesMap)
        }
    }
}