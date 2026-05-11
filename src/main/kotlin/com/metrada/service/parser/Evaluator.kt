package com.metrada.service.parser

import kotlin.math.*

class Evaluator(
    private val querier: Querier,
    private val engine: Engine,
    private val startTs: Long,
    private val endTs: Long,
    private val interval: Long,
) {

    private var currentSamples = 0

    suspend fun eval(expr: Expr): Value = when (expr) {
        is NumberLiteral -> Value.Scalar(startTs, expr.value)
        is StringLiteral -> Value.String(startTs, expr.value)
        is VectorSelector -> evalVectorSelector(expr)
        is MatrixSelector -> evalMatrixSelector(expr)
        is AggregateExpr -> evalAggregate(expr)
        is Call -> evalCall(expr)
        is BinaryExpr -> evalBinary(expr)
        is ParenExpr -> eval(expr.expr)
        is UnaryExpr -> evalUnary(expr)
        is SubqueryExpr -> evalSubquery(expr)
        is StepInvariantExpr -> evalStepInvariant(expr)
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
        val (mint, maxt) = calculateTimeRange(vs)
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
            if (pt.timestamp > maxt - offset) break
            result.add(FPoint(pt.timestamp + offset, pt.value))
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
//        val (_, end) = calculateTimeRange(vs)
//        val start = end - range
//        return start to end
        return calculateTimeRange(vs)
    }

    // -------- Агрегации (sum, avg, count, min, max, topk, bottomk) ----------
    private suspend fun evalAggregate(agg: AggregateExpr): Value {
        val inputMatrix = (eval(agg.expr) as Value.Matrix).series
        val grouping = agg.grouping.sorted()
        val without = agg.without

        // Маппинг: ключ группы -> временная метка -> аккумулятор
        val groupMap = mutableMapOf<Long, MutableMap<Long, GroupAccumulator>>()
        val groupLabels = mutableMapOf<Long, Labels>()

        for (series in inputMatrix) {
            val key = computeGroupKey(series.labels, grouping, without)
            if (!groupLabels.containsKey(key)) {
                groupLabels[key] = buildOutputLabels(series.labels, grouping, without)
            }
            val timeMap = groupMap.getOrPut(key) { mutableMapOf() }
            for (point in series.points) {
                val acc = timeMap.getOrPut(point.timestamp) { GroupAccumulator() }
                acc.count++
                when (agg.op) {
                    AggrOp.SUM -> acc.sum += point.value
                    AggrOp.AVG -> {
                        acc.sum += point.value; acc.avgCount++
                    }

                    AggrOp.COUNT -> acc.countVal++
                    AggrOp.MIN -> acc.min = min(acc.min, point.value)
                    AggrOp.MAX -> acc.max = max(acc.max, point.value)
                    AggrOp.TOPK -> {
                        val k = (agg.param as? NumberLiteral)?.value?.toInt() ?: 1
                        acc.topKHeap.add(point.value)
                        if (acc.topKHeap.size > k) acc.topKHeap.remove()
                    }

                    AggrOp.BOTTOMK -> {
                        val k = (agg.param as? NumberLiteral)?.value?.toInt() ?: 1
                        acc.bottomKHeap.add(point.value)
                        if (acc.bottomKHeap.size > k) acc.bottomKHeap.remove()
                    }

                    else -> {}
                }
            }
        }

        // Преобразуем в выходные серии
        val outputSeries = groupMap.map { (key, timeMap) ->
            val labels = groupLabels[key]!!
            val points = timeMap.map { (ts, acc) ->
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
                FPoint(ts, value)
            }.sortedBy { it.timestamp }
            Series(labels, points.toMutableList())
        }

        return Value.Matrix(outputSeries)
    }

    private fun computeGroupKey(metric: Labels, grouping: List<String>, without: Boolean): Long {
        return if (without) metric.without(*grouping.toTypedArray()).hash()
        else if (grouping.isEmpty()) 0L
        else metric.keep(*grouping.toTypedArray()).hash()
    }

    private fun buildOutputLabels(metric: Labels, grouping: List<String>, without: Boolean): Labels {
        return if (without) metric.without(*grouping.toTypedArray())
        else if (grouping.isEmpty()) Labels(emptyMap())
        else metric.keep(*grouping.toTypedArray())
    }

    // -------- Функции (пример rate) ----------
    private suspend fun evalCall(call: Call): Value {
        return when (call.func.name) {
            "rate" -> evalRate(call.args[0] as MatrixSelector)
            "irate" -> evalIrate(call.args[0] as MatrixSelector)
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
        val range = ms.range
        val numSteps = ((endTs - startTs) / interval).toInt().coerceAtLeast(1)
        val resultMap = mutableMapOf<Labels, MutableList<FPoint>>()

        for (stepIdx in 0 until numSteps) {
            val ts = startTs + stepIdx * interval
            val windowStart = ts - range

            for (series in matrix.series) {
                val points = series.points
                val windowPoints = points.filter { it.timestamp in windowStart..ts }
                if (windowPoints.size < 2) continue
                val first = windowPoints.first()
                val last = windowPoints.last()
                val dt = (last.timestamp - first.timestamp).toDouble() / 1000.0
                if (dt <= 0) continue
                val rateValue = (last.value - first.value) / dt
                resultMap.getOrPut(series.labels) { mutableListOf() }.add(FPoint(ts, rateValue))
            }
        }

        val result = resultMap.map { (labels, points) -> Series(labels, points.toMutableList()) }
        return Value.Matrix(result)
    }

    private suspend fun evalIrate(ms: MatrixSelector): Value {
        val matrix = eval(ms) as Value.Matrix
        val range = ms.range
        val numSteps = ((endTs - startTs) / interval).toInt().coerceAtLeast(1)
        val resultMap = mutableMapOf<Labels, MutableList<FPoint>>()

        for (stepIdx in 0 until numSteps) {
            val ts = startTs + stepIdx * interval
            val windowStart = ts - range

            for (series in matrix.series) {
                val points = series.points
                val windowPoints = points.filter { it.timestamp in windowStart..ts }
                if (windowPoints.size < 2) continue
                val prev = windowPoints[windowPoints.size - 2]
                val last = windowPoints.last()
                val dt = (last.timestamp - prev.timestamp).toDouble() / 1000.0
                if (dt <= 0) continue
                val irateValue = (last.value - prev.value) / dt
                resultMap.getOrPut(series.labels) { mutableListOf() }.add(FPoint(ts, irateValue))
            }
        }

        val result = resultMap.map { (labels, points) -> Series(labels, points.toMutableList()) }
        return Value.Matrix(result)
    }

    private suspend fun evalIncrease(ms: MatrixSelector): Value {
        val matrix = eval(ms) as Value.Matrix
        val range = ms.range
        val numSteps = ((endTs - startTs) / interval).toInt().coerceAtLeast(1)
        val resultMap = mutableMapOf<Labels, MutableList<FPoint>>()

        for (stepIdx in 0 until numSteps) {
            val ts = startTs + stepIdx * interval
            val windowStart = ts - range

            for (series in matrix.series) {
                val points = series.points
                val windowPoints = points.filter { it.timestamp in windowStart..ts }
                if (windowPoints.size < 2) continue
                val first = windowPoints.first()
                val last = windowPoints.last()
                val increaseValue = last.value - first.value
                resultMap.getOrPut(series.labels) { mutableListOf() }.add(FPoint(ts, increaseValue))
            }
        }

        val result = resultMap.map { (labels, points) -> Series(labels, points.toMutableList()) }
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
            is Value.Scalar -> Value.Scalar(inner.timestamp, op(inner.value))
            is Value.Vector -> {
                val samples = inner.samples.map { it.copy(f = op(it.f)) }
                Value.Vector(samples)
            }

            is Value.Matrix -> {
                val series = inner.series.map { s ->
                    Series(s.labels, s.points.map { p -> FPoint(p.timestamp, op(p.value)) }.toMutableList())
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
                val v = scalarBinop(bin.op, lhs.value, rhs.value)
                Value.Scalar(max(lhs.timestamp, rhs.timestamp), v)
            }

            lhs is Value.Vector && rhs is Value.Scalar -> {
                val samples = lhs.samples.map { s ->
                    val v = scalarBinop(bin.op, s.f, rhs.value)
                    s.copy(f = v)
                }
                Value.Vector(samples)
            }

            lhs is Value.Scalar && rhs is Value.Vector -> {
                val samples = rhs.samples.map { s ->
                    val v = scalarBinop(bin.op, lhs.value, s.f)
                    s.copy(f = v)
                }
                Value.Vector(samples)
            }

            lhs is Value.Vector && rhs is Value.Vector -> {
                // Сложение векторов – сопоставление по меткам (упрощённо)
                val mapRhs = rhs.samples.associateBy { it.labels.hash() }
                val samples = lhs.samples.mapNotNull { ls ->
                    mapRhs[ls.labels.hash()]?.let { rs ->
                        val v = scalarBinop(bin.op, ls.f, rs.f)
                        ls.copy(f = v)
                    }
                }
                Value.Vector(samples)
            }

            lhs is Value.Matrix && rhs is Value.Scalar -> {
                val newSeries = lhs.series.map { series ->
                    val newPoints = series.points.map { point ->
                        FPoint(point.timestamp, scalarBinop(bin.op, point.value, rhs.value))
                    }
                    Series(series.labels, newPoints.toMutableList())
                }
                Value.Matrix(newSeries)
            }

            lhs is Value.Scalar && rhs is Value.Matrix -> {
                val newSeries = rhs.series.map { series ->
                    val newPoints = series.points.map { point ->
                        FPoint(point.timestamp, scalarBinop(bin.op, lhs.value, point.value))
                    }
                    Series(series.labels, newPoints.toMutableList())
                }
                Value.Matrix(newSeries)
            }

            lhs is Value.Matrix && rhs is Value.Matrix -> {
                // Предполагаем, что серии имеют одинаковые метки и одинаковые временные метки
                val newSeries = lhs.series.zip(rhs.series).map { (lseries, rseries) ->
                    val points = lseries.points.zip(rseries.points).map { (lp, rp) ->
                        if (lp.timestamp != rp.timestamp) {
                            // можно более сложное выравнивание, но для простоты считаем, что совпадают
                            error("Timestamps mismatch in matrix binary op")
                        }
                        FPoint(lp.timestamp, scalarBinop(bin.op, lp.value, rp.value))
                    }.toMutableList()
                    Series(lseries.labels, points)
                }
                Value.Matrix(newSeries)
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
                is Value.Scalar -> Value.Scalar(inner.timestamp, -inner.value)
                is Value.Vector -> Value.Vector(inner.samples.map { it.copy(f = -it.f) })
                is Value.Matrix -> Value.Matrix(inner.series.map { s ->
                    Series(s.labels, s.points.map { p -> FPoint(p.timestamp, -p.value) }.toMutableList())
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