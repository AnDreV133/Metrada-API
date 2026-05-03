package com.metrada.service.parser

import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

@Component
class Engine(
    val maxSamples: Int = 50_000_000,
    val timeoutMillis: Long = 120_000,
    val lookbackDelta: Long = 5 * 60 * 1000L,
    val enableAtModifier: Boolean = true,
    val enableNegativeOffset: Boolean = true
) {
    suspend fun newInstantQuery(querier: Querier, query: String, ts: Long): Result {
        val expr = PromQLParser.parse(query.replace("=~","=").replace("!~", "!="))
//        val preprocessed = Preprocessor.preprocess(expr, ts, ts, 0)
        val eval = Evaluator(querier, this, ts, ts, 1)
        val value = withTimeout(timeoutMillis) { eval.eval(expr) }
        return Result(value, emptyList())
    }

    suspend fun newRangeQuery(querier: Querier, query: String, start: Long, end: Long, step: Long): Result {
        val expr = PromQLParser.parse(query)
        require(expr.type() == ValueTypeEnum.Vector || expr.type() == ValueTypeEnum.Scalar) {
            "range query requires scalar or instant vector"
        }
//        val preprocessed = Preprocessor.preprocess(expr, start, end, step)
        val eval = Evaluator(querier, this, start, end, step)
        val value = withTimeout(timeoutMillis) { eval.eval(expr) }
        return Result(value, emptyList())
    }
}

data class Result(val value: Value, val warnings: List<Throwable>)