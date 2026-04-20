package com.metrada

import com.metrada.service.MetricQueryService
import com.metrada.util.math.ExpressionParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
class ExpressionMathSeriesUtilIntegrationTest {

//    @Autowired
//    private lateinit var metricService: MetricQueryService  // ваш сервис для работы с БД
//
//    @Test
//    fun `should calculate CPU usage from database metrics`() {
//        // 1. Запрашиваем данные из БД
//        val cpuIdleSeries = metricService.queryMetrics(
//            metricName = "node_cpu_seconds_total",
//            tagFilters = mapOf("mode" to "idle"),
//            startTime = Instant.now().minusSeconds(3600),
//            endTime = Instant.now()
//        )
//
//        // 2. Применяем PromQL функции через парсер
//        val result = ExpressionParser.parse(
//            "clamp_min(multiply_scalar(subtract(vector(1), avg(rate(idle, 15, 60))), 100), 0)",
//            mapOf("idle" to cpuIdleSeries.values)
//        ) as List<Double>
//
//        // 3. Проверяем результат
//        assertNotNull(result)
//        assertTrue(result.all { it in 0.0..100.0 })
//    }
}