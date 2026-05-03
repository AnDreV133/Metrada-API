package com.metrada.service.parser

import kotlin.math.*

class Evaluator(
    private val querier: Querier,
    private val engine: Engine,
    private val startTs: Long,
    private val endTs: Long,
    private val interval: Long
) {
    private var currentSamples = 0

    suspend fun eval(expr: Expr): Value = when (expr) {
        is NumberLiteral -> Value.Scalar(startTs, expr.value)
        is StringLiteral -> Value.String(expr.value, startTs)
        is VectorSelector -> evalVectorSelector(expr)
        is MatrixSelector -> evalMatrixSelector(expr)
        is AggregateExpr -> evalAggregate(expr)
        is Call -> evalCall(expr)
        is BinaryExpr -> evalBinary(expr)
        is ParenExpr -> eval(expr.expr)
        is UnaryExpr -> evalUnary(expr)
        is SubqueryExpr -> evalSubquery(expr)
        is StepInvariantExpr -> evalStepInvariant(expr)
        else -> error("unsupported expr: ${expr::class}")
    }

    // -------- Векторный селектор (мгновенный) ----------
    private suspend fun evalVectorSelector(sel: VectorSelector): Value.Matrix {
        val (mint, maxt) = calculateTimeRange(sel)
        val seriesSet = querier.select(mint, maxt, sel.labelMatchers)
        val matrix = mutableListOf<Series>()
        while (seriesSet.next()) {
            val storageSeries = seriesSet.at()
            val points = readPoints(storageSeries, mint, maxt, sel.originalOffset)
            if (points.isNotEmpty()) {
                matrix.add(Series(storageSeries.labels(), points.toMutableList()))
            }
        }
        return Value.Matrix(matrix)
    }

    // -------- Матричный селектор (диапазонный) ----------
    private suspend fun evalMatrixSelector(sel: MatrixSelector): Value.Matrix {
        val vs = sel.vectorSelector
        val (mint, maxt) = calculateTimeRangeForMatrix(vs, sel.range)
        val seriesSet = querier.select(mint, maxt, vs.labelMatchers)
        val matrix = mutableListOf<Series>()
        while (seriesSet.next()) {
            val storageSeries = seriesSet.at()
            val points = readPoints(storageSeries, mint, maxt, vs.originalOffset)
            matrix.add(Series(storageSeries.labels(), points.toMutableList()))
        }
        return Value.Matrix(matrix)
    }

    // -------- Чтение точек из одной серии ----------
    private fun readPoints(series: IStorageSeries, mint: Long, maxt: Long, offset: Long): List<FPoint> {
        val it = series.iterator()
        val result = mutableListOf<FPoint>()
        var found = it.seek(mint - offset)
        while (found) {
            val pt = it.at()
            if (pt.t > maxt - offset) break
            result.add(FPoint(pt.t + offset, pt.f))
            found = it.next()
        }
        currentSamples += result.size
        return result
    }

    // -------- Вычисление временных границ ----------
    private fun calculateTimeRange(sel: VectorSelector): Pair<Long, Long> {
        var start = startTs
        var end = endTs
        if (sel.timestamp != null) {
            start = sel.timestamp
            end = sel.timestamp
        }
        val offset = sel.originalOffset
        start -= offset
        end -= offset
        if (!sel.smoothed) {
            start -= (engine.lookbackDelta - 1)
        }
        return start to end
    }

    private fun calculateTimeRangeForMatrix(vs: VectorSelector, range: Long): Pair<Long, Long> {
        val (_, end) = calculateTimeRange(vs)
        val start = end - range
        return start to end
    }

    // -------- Агрегации (sum, avg, count, min, max, topk, bottomk) ----------
    private suspend fun evalAggregate(agg: AggregateExpr): Value {
        val inputMatrix = (eval(agg.expr) as Value.Matrix).series
        val numSteps = ((endTs - startTs) / interval).toInt() + 1
        val grouping = agg.grouping.sorted()
        val without = agg.without

        // Группировка: mapping ключ группировки -> индекс в выходной матрице
        val groupMap = mutableMapOf<Long, MutableList<Int>>()
        val outputSeries = mutableListOf<Series>()

//        for (series in inputMatrix) {
//            val key = computeGroupKey(series.metric, grouping, without)
//            val idx = groupMap.getOrPut(key) {
//                val metric = buildOutputMetric(series.metric, grouping, without)
//                outputSeries.add(Series(metric, mutableListOf()))
//                outputSeries.size - 1
//            } // todo wtf
//        }

        // Обработка каждого временного шага
        for (stepIdx in 0 until numSteps) {
            val ts = startTs + stepIdx * interval
            val accum = Array(outputSeries.size) { GroupAccumulator() }

            for (series in inputMatrix) {
                val point = getPointAtTime(series, ts) ?: continue
                val key = computeGroupKey(series.metric, grouping, without)
                val groupIdx = groupMap[key]!!.first()
                val acc = accum[groupIdx]
                acc.count++
                when (agg.op) {
                    AggrOp.SUM -> acc.sum += point.f
                    AggrOp.AVG -> { acc.sum += point.f; acc.avgCount++ }
                    AggrOp.COUNT -> acc.countVal++
                    AggrOp.MIN -> acc.min = min(acc.min, point.f)
                    AggrOp.MAX -> acc.max = max(acc.max, point.f)
                    AggrOp.TOPK -> {
                        val k = (agg.param as? NumberLiteral)?.value?.toInt() ?: 1
                        acc.topKHeap.add(point.f)
                        if (acc.topKHeap.size > k) acc.topKHeap.remove()
                    }
                    AggrOp.BOTTOMK -> {
                        val k = (agg.param as? NumberLiteral)?.value?.toInt() ?: 1
                        acc.bottomKHeap.add(point.f)
                        if (acc.bottomKHeap.size > k) acc.bottomKHeap.remove()
                    }
                    else -> {}
                }
            }

            // Формируем точки для каждого выходного ряда
            for ((idx, acc) in accum.withIndex()) {
                if (acc.count == 0) continue
                val value = when (agg.op) {
                    AggrOp.SUM -> acc.sum
                    AggrOp.AVG -> acc.sum / acc.avgCount
                    AggrOp.COUNT -> acc.countVal.toDouble()
                    AggrOp.MIN -> acc.min
                    AggrOp.MAX -> acc.max
                    AggrOp.TOPK -> acc.topKHeap.sortedDescending().firstOrNull() ?: Double.NaN
                    AggrOp.BOTTOMK -> acc.bottomKHeap.sorted().firstOrNull() ?: Double.NaN
                    else -> Double.NaN
                }
                addPoint(outputSeries[idx], ts, value)
            }
        }

        return Value.Matrix(outputSeries)
    }

    private fun getPointAtTime(series: Series, ts: Long): FPoint? {
        // Предполагаем, что точки отсортированы по времени и мы потребляем их последовательно (в реальном evaluator нужно хранить позицию)
        // Упрощённо: линейный поиск (в реальном проекте используйте итератор)
        return series.points.find { it.t == ts }
    }

    private fun computeGroupKey(metric: Labels, grouping: List<String>, without: Boolean): Long {
        return if (without) metric.without(*grouping.toTypedArray()).hash()
        else if (grouping.isEmpty()) 0L
        else metric.keep(*grouping.toTypedArray()).hash()
    }

    private fun buildOutputMetric(metric: Labels, grouping: List<String>, without: Boolean): Labels {
        return if (without) metric.without(*grouping.toTypedArray())
        else if (grouping.isEmpty()) Labels(emptyMap())
        else metric.keep(*grouping.toTypedArray())
    }

    private fun addPoint(series: Series, ts: Long, value: Double) {
        series.points.add(FPoint(ts, value))
        currentSamples++
    }

    // -------- Функции (пример rate) ----------
    private suspend fun evalCall(call: Call): Value {
        return when (call.func.name) {
            "rate" -> evalRate(call.args[0] as MatrixSelector)
            "increase" -> evalIncrease(call.args[0] as MatrixSelector)
            "sum", "avg", "count", "min", "max", "topk", "bottomk" -> evalCallAggregation(call)
            "abs" -> evalUnaryFloatOp(call.args[0]) { abs(it) }
            "ceil" -> evalUnaryFloatOp(call.args[0]) { ceil(it) }
            "floor" -> evalUnaryFloatOp(call.args[0]) { floor(it) }
            "round" -> evalUnaryFloatOp(call.args[0]) { round(it) }
            "sqrt" -> evalUnaryFloatOp(call.args[0]) { sqrt(it) }
            else -> error("unsupported function: ${call.func.name}")
        }
    }

    private suspend fun evalRate(ms: MatrixSelector): Value {
        val matrix = eval(ms) as Value.Matrix
        val result = mutableListOf<Series>()
        for (series in matrix.series) {
            val points = series.points
            if (points.size < 2) continue
            val newPoints = mutableListOf<FPoint>()
            for (i in 1 until points.size) {
                val dt = points[i].t - points[i-1].t // ms
                if (dt <= 0) continue
                val rate = (points[i].f - points[i-1].f) * 1000.0 / dt
                newPoints.add(FPoint(points[i].t, rate))
            }
            result.add(Series(series.metric, newPoints))
        }
        return Value.Matrix(result)
    }

    private suspend fun evalIncrease(ms: MatrixSelector): Value {
        val matrix = eval(ms) as Value.Matrix
        val result = mutableListOf<Series>()
        for (series in matrix.series) {
            val points = series.points
            if (points.size < 2) continue
            val increase = points.last().f - points.first().f
            result.add(Series(series.metric, mutableListOf(FPoint(points.last().t, increase))))
        }
        return Value.Matrix(result)
    }

    private suspend fun evalCallAggregation(call: Call): Value {
        // Превращаем в AggregateExpr и вызываем evalAggregate
        val op = when (call.func.name) {
            "sum" -> AggrOp.SUM
            "avg" -> AggrOp.AVG
            "count" -> AggrOp.COUNT
            "min" -> AggrOp.MIN
            "max" -> AggrOp.MAX
            "topk" -> AggrOp.TOPK
            "bottomk" -> AggrOp.BOTTOMK
            else -> error("not an aggregation")
        }
        val param = if (op in setOf(AggrOp.TOPK, AggrOp.BOTTOMK)) call.args[0] else null
        val expr = call.args.last()
        val agg = AggregateExpr(op, param, expr, emptyList(), false, PosRange.EMPTY)
        return evalAggregate(agg)
    }

    private suspend fun evalUnaryFloatOp(arg: Expr, op: (Double) -> Double): Value {
        val inner = eval(arg)
        return when (inner) {
            is Value.Scalar -> Value.Scalar(inner.t, op(inner.v))
            is Value.Vector -> {
                val samples = inner.samples.map { it.copy(f = op(it.f)) }
                Value.Vector(samples)
            }
            is Value.Matrix -> {
                val series = inner.series.map { s ->
                    Series(s.metric, s.points.map { p -> FPoint(p.t, op(p.f)) }.toMutableList())
                }
                Value.Matrix(series)
            }
            else -> error("unsupported type for unary float op")
        }
    }

    // -------- Бинарные операции (упрощённо) ----------
    private suspend fun evalBinary(bin: BinaryExpr): Value {
        val lhs = eval(bin.lhs)
        val rhs = eval(bin.rhs)
        return when {
            lhs is Value.Scalar && rhs is Value.Scalar -> {
                val v = scalarBinop(bin.op, lhs.v, rhs.v)
                Value.Scalar(max(lhs.t, rhs.t), v)
            }
            lhs is Value.Vector && rhs is Value.Scalar -> {
                val samples = lhs.samples.map { s ->
                    val v = scalarBinop(bin.op, s.f, rhs.v)
                    s.copy(f = v)
                }
                Value.Vector(samples)
            }
            lhs is Value.Scalar && rhs is Value.Vector -> {
                val samples = rhs.samples.map { s ->
                    val v = scalarBinop(bin.op, lhs.v, s.f)
                    s.copy(f = v)
                }
                Value.Vector(samples)
            }
            lhs is Value.Vector && rhs is Value.Vector -> {
                // Сложение векторов – сопоставление по меткам (упрощённо)
                val mapRhs = rhs.samples.associateBy { it.metric.hash() }
                val samples = lhs.samples.mapNotNull { ls ->
                    mapRhs[ls.metric.hash()]?.let { rs ->
                        val v = scalarBinop(bin.op, ls.f, rs.f)
                        ls.copy(f = v)
                    }
                }
                Value.Vector(samples)
            }
            else -> error("incompatible types for binary op")
        }
    }

    private fun scalarBinop(op: OpType, a: Double, b: Double): Double = when (op) {
        OpType.ADD -> a + b
        OpType.SUB -> a - b
        OpType.MUL -> a * b
        OpType.DIV -> a / b
        OpType.POW -> a.pow(b)
        OpType.MOD -> a % b
        OpType.EQLC -> if (a == b) 1.0 else 0.0
        OpType.NEQ -> if (a != b) 1.0 else 0.0
        OpType.GTR -> if (a > b) 1.0 else 0.0
        OpType.LSS -> if (a < b) 1.0 else 0.0
        OpType.GTE -> if (a >= b) 1.0 else 0.0
        OpType.LTE -> if (a <= b) 1.0 else 0.0
        else -> error("unsupported binop")
    }

    // -------- Остальные узлы (упрощённо) ----------
    private suspend fun evalUnary(unary: UnaryExpr): Value {
        val inner = eval(unary.expr)
        return when (unary.op) {
            OpType.SUB -> when (inner) {
                is Value.Scalar -> Value.Scalar(inner.t, -inner.v)
                is Value.Vector -> Value.Vector(inner.samples.map { it.copy(f = -it.f) })
                is Value.Matrix -> Value.Matrix(inner.series.map { s ->
                    Series(s.metric, s.points.map { p -> FPoint(p.t, -p.f) }.toMutableList())
                })
                else -> error("unsupported unary -")
            }
            OpType.ADD -> inner
            else -> error("unsupported unary operator")
        }
    }

    private suspend fun evalSubquery(subq: SubqueryExpr): Value = eval(subq.expr)

    private suspend fun evalStepInvariant(stepInv: StepInvariantExpr): Value = eval(stepInv.expr)

    // Вспомогательный класс для агрегации
    private class GroupAccumulator {
        var count = 0
        var sum = 0.0
        var avgCount = 0
        var countVal = 0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        val topKHeap = java.util.PriorityQueue<Double>()
        val bottomKHeap = java.util.PriorityQueue<Double>(reverseOrder())
    }
}