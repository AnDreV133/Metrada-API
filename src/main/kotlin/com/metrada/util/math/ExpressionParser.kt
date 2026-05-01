package com.metrada.util.math

import com.metrada.model.MetricReference

/**
 * Stateless парсер PromQL-подобных запросов.
 * Поддерживает бинарные операторы, функции, селекторы с тегами и интервалами.
 */
object ExpressionParser {

    private const val DEFAULT_RANGE = "1m"
    private val DEFAULT_RANGE_SECONDS = parseDuration(DEFAULT_RANGE)

    // Приоритеты операторов (чем выше, тем выше приоритет)
    private val operatorPrecedence = mapOf(
        "or" to 10, "unless" to 10,
        "and" to 15,
        "==" to 20, "!=" to 20,
        ">" to 30, ">=" to 30, "<" to 30, "<=" to 30,
        "+" to 40, "-" to 40,
        "*" to 50, "/" to 50, "%" to 50,
        "^" to 60
    )

    private val leftAssociative = setOf("+", "-", "*", "/", "%", "==", "!=", ">", ">=", "<", "<=", "and", "or", "unless")
    private val rightAssociative = setOf("^")

    // Список зарезервированных функций (чтобы не путать с метриками)
    private val reservedFunctions = setOf(
        "rate", "irate", "increase", "delta",
        "sum", "avg", "min", "max", "stddev", "count",
        "topk", "bottomk",
        "avg_over_time", "sum_over_time", "max_over_time", "min_over_time",
        "count_over_time", "quantile_over_time", "stddev_over_time",
        "abs", "floor", "ceil", "round", "clamp_min", "clamp_max",
        "scalar", "vector",
        "add", "subtract", "multiply", "divide", "multiply_scalar", "add_scalar",
        "greater_than", "less_than", "greater_or_equal", "less_or_equal", "equal",
        "histogram_quantile"
    )

    // ==================== Публичные методы ====================

    /**
     * Разбирает и вычисляет PromQL-выражение.
     * @param query PromQL запрос.
     * @param seriesMap карта "селектор -> значения" (селектор включает теги и интервал).
     * @return результат вычисления (Double, List<Double> или List<List<Double>>).
     */
    fun parse(query: String, seriesMap: Map<String, List<Double>>): Any {
        val normalized = normalizeQuery(query)
        val tokens = tokenize(normalized)
        val ast = parseExpression(tokens, 0)
        return evaluate(ast, seriesMap)
    }

    /**
     * Извлекает все ссылки на метрики из запроса.
     * Возвращает список MetricReference с именем, тегами и интервалом в секундах.
     */
    fun extractMetricReferences(query: String): List<MetricReference> {
        val normalized = normalizeQuery(query)
        val pattern = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)(?:\{([^}]+)\})?(?:\[([^\]]+)\])?""")
        return pattern.findAll(normalized).mapNotNull { match ->
            val name = match.groupValues[1]
            if (name in reservedFunctions) return@mapNotNull null
            val tags = if (match.groupValues[2].isNotEmpty()) parseTags(match.groupValues[2]) else emptyMap()
            val range = match.groupValues[3].takeIf { it.isNotEmpty() }?.let { parseDuration(it) } ?: DEFAULT_RANGE_SECONDS
            MetricReference(name, tags, range)
        }.toList()
    }

    fun extractMetricNames(query: String): List<String> = extractMetricReferences(query).map { it.name }.distinct()

    fun parseDuration(duration: String): Long {
        val number = duration.dropLast(1).toDoubleOrNull() ?: return 0
        return when (duration.last()) {
            's' -> number.toLong()
            'm' -> (number * 60).toLong()
            'h' -> (number * 3600).toLong()
            'd' -> (number * 86400).toLong()
            'w' -> (number * 604800).toLong()
            else -> 0
        }
    }

    // ==================== Нормализация запроса ====================

    private fun normalizeQuery(query: String): String {
        // Добавляем интервал по умолчанию ко всем селекторам, у которых его нет
        val pattern = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)(?:\{[^}]*\})?(?![^\[]*\])""")
        var result = query
        while (true) {
            val match = pattern.find(result) ?: break
            val full = match.value
            if (full !in reservedFunctions && full !in operatorPrecedence && full != "(" && full != ")") {
                result = result.replaceFirst(full, "$full[$DEFAULT_RANGE]")
            } else {
                break
            }
        }
        return result
    }

    private fun parseTags(tagStr: String): Map<String, String> {
        val tagPattern = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)=["']([^"']*)["']""")
        return tagPattern.findAll(tagStr).associate { it.groupValues[1] to it.groupValues[2] }
    }

    // ==================== Токенизация ====================

    private enum class TokenType { IDENTIFIER, NUMBER, BINARY_OP, LPAREN, RPAREN, COMMA }

    private data class Token(val type: TokenType, val value: Any)

    private fun tokenize(expr: String): List<Token> {
        val regex = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)|(\d+(?:\.\d+)?)|([+\-*/%^()=!<>,]+)|(and|or|unless)""")
        return regex.findAll(expr).mapNotNull { match ->
            when {
                match.value.matches(Regex("""\d+(\.\d+)?""")) -> Token(TokenType.NUMBER, match.value.toDouble())
                match.value.matches(Regex("""[a-zA-Z_][a-zA-Z0-9_]*""")) -> {
                    val word = match.value
                    if (word in operatorPrecedence) Token(TokenType.BINARY_OP, word)
                    else Token(TokenType.IDENTIFIER, word)
                }
                match.value in setOf("and", "or", "unless") -> Token(TokenType.BINARY_OP, match.value)
                match.value == "(" -> Token(TokenType.LPAREN, "(")
                match.value == ")" -> Token(TokenType.RPAREN, ")")
                match.value == "," -> Token(TokenType.COMMA, ",")
                match.value.matches(Regex("""[=!<>]=?""")) -> Token(TokenType.BINARY_OP, match.value)
                match.value.matches(Regex("""[+\-*/%^]""")) -> Token(TokenType.BINARY_OP, match.value)
                else -> null
            }
        }.toList()
    }

    // ==================== Парсинг (рекурсивный спуск) ====================

    private class ParserState(val tokens: List<Token>, var pos: Int)

    private fun parseExpression(state: ParserState, minPrecedence: Int = 0): ASTNode {
        var left = parsePrimary(state)
        while (state.pos < state.tokens.size) {
            val token = state.tokens[state.pos]
            if (token.type != TokenType.BINARY_OP) break
            val op = token.value as String
            val prec = operatorPrecedence[op] ?: break
            if (prec < minPrecedence) break
            state.pos++ // съедаем оператор
            val nextMinPrec = if (op in rightAssociative) prec else prec + 1
            val right = parseExpression(state, nextMinPrec)
            left = BinaryOpNode(op, left, right)
        }
        return left
    }

    private fun parsePrimary(state: ParserState): ASTNode {
        val token = state.tokens[state.pos]
        return when (token.type) {
            TokenType.NUMBER -> {
                state.pos++
                LiteralNode(token.value as Double)
            }
            TokenType.IDENTIFIER -> {
                val name = token.value as String
                state.pos++
                // Если следующий токен "(", то это функция
                if (state.pos < state.tokens.size && state.tokens[state.pos].type == TokenType.LPAREN) {
                    parseFunctionCall(state, name)
                } else {
                    // Селектор (уже содержит теги и интервал после нормализации)
                    VectorSelectorNode(name)
                }
            }
            TokenType.LPAREN -> {
                state.pos++ // пропускаем '('
                val expr = parseExpression(state)
                if (state.pos >= state.tokens.size || state.tokens[state.pos].type != TokenType.RPAREN) {
                    error("Missing closing ')'")
                }
                state.pos++ // пропускаем ')'
                ParenNode(expr)
            }
            else -> error("Unexpected token ${token.type} at position ${state.pos}")
        }
    }

    private fun parseFunctionCall(state: ParserState, funcName: String): ASTNode {
        // предполагаем, что текущий токен '(' уже не съеден, но мы его съедим сейчас
        require(state.tokens[state.pos].type == TokenType.LPAREN) { "Expected '(' after function name" }
        state.pos++ // пропускаем '('
        val args = mutableListOf<ASTNode>()
        while (state.pos < state.tokens.size && state.tokens[state.pos].type != TokenType.RPAREN) {
            val arg = parseExpression(state)
            args.add(arg)
            if (state.pos < state.tokens.size && state.tokens[state.pos].type == TokenType.COMMA) {
                state.pos++ // пропускаем ','
            }
        }
        if (state.pos >= state.tokens.size || state.tokens[state.pos].type != TokenType.RPAREN) {
            error("Missing closing ')' in function call")
        }
        state.pos++ // пропускаем ')'
        return FunctionCallNode(funcName, args)
    }

    // ==================== AST узлы ====================

    private sealed class ASTNode
    private data class LiteralNode(val value: Double) : ASTNode()
    private data class VectorSelectorNode(val key: String) : ASTNode()
    private data class BinaryOpNode(val op: String, val left: ASTNode, val right: ASTNode) : ASTNode()
    private data class FunctionCallNode(val name: String, val args: List<ASTNode>) : ASTNode()
    private data class ParenNode(val inner: ASTNode) : ASTNode()

    // ==================== Вычисление ====================

    private fun evaluate(node: ASTNode, seriesMap: Map<String, List<Double>>): Any = when (node) {
        is LiteralNode -> node.value
        is VectorSelectorNode -> seriesMap[node.key] ?: error("Series not found: ${node.key}")
        is BinaryOpNode -> {
            val left = evaluate(node.left, seriesMap)
            val right = evaluate(node.right, seriesMap)
            applyBinaryOperator(node.op, left, right)
        }
        is FunctionCallNode -> {
            val args = node.args.map { evaluate(it, seriesMap) }
            executeFunction(node.name, args)
        }
        is ParenNode -> evaluate(node.inner, seriesMap)
    }

    private fun applyBinaryOperator(op: String, left: Any, right: Any): Any {
        val leftVec = when (left) {
            is Double -> listOf(left)
            is List<*> -> @Suppress("UNCHECKED_CAST") (left as List<Double>)
            else -> error("Left operand must be number or vector")
        }
        val rightVec = when (right) {
            is Double -> listOf(right)
            is List<*> -> @Suppress("UNCHECKED_CAST") (right as List<Double>)
            else -> error("Right operand must be number or vector")
        }
        val size = minOf(leftVec.size, rightVec.size)
        return when (op) {
            "+" -> (0 until size).map { leftVec[it] + rightVec[it] }
            "-" -> (0 until size).map { leftVec[it] - rightVec[it] }
            "*" -> (0 until size).map { leftVec[it] * rightVec[it] }
            "/" -> (0 until size).map { if (rightVec[it] != 0.0) leftVec[it] / rightVec[it] else Double.NaN }
            ">", ">=", "<", "<=", "==", "!=" -> {
                val cmp: (Double, Double) -> Boolean = when (op) {
                    ">" -> { a, b -> a > b }
                    ">=" -> { a, b -> a >= b }
                    "<" -> { a, b -> a < b }
                    "<=" -> { a, b -> a <= b }
                    "==" -> { a, b -> a == b }
                    "!=" -> { a, b -> a != b }
                    else -> { _, _ -> false }
                }
                (0 until size).map { if (cmp(leftVec[it], rightVec[it])) 1.0 else 0.0 }
            }
            "and" -> (0 until size).map { if (leftVec[it] != 0.0 && rightVec[it] != 0.0) 1.0 else 0.0 }
            "or" -> (0 until size).map { if (leftVec[it] != 0.0 || rightVec[it] != 0.0) 1.0 else 0.0 }
            "unless" -> (0 until size).map { if (leftVec[it] != 0.0 && rightVec[it] == 0.0) 1.0 else 0.0 }
            else -> error("Unsupported operator $op")
        }
    }

    private fun executeFunction(name: String, args: List<Any>): Any = when (name) {
        "rate" -> {
            val values = args[0] as List<Double>
            val step = (args[1] as Double).toLong()
            val window = (args[2] as Double).toLong()
            MathSeriesUtil.rate(values, step, window)
        }
        "sum" -> MathSeriesUtil.sum(args.map { it as List<Double> })
        "avg" -> MathSeriesUtil.avg(args.map { it as List<Double> })
        "min" -> MathSeriesUtil.min(args.map { it as List<Double> })
        "max" -> MathSeriesUtil.max(args.map { it as List<Double> })
        "count" -> MathSeriesUtil.count(args.map { it as List<Double> })
        "stddev" -> MathSeriesUtil.stddev(args.map { it as List<Double> })
        "topk" -> {
            val k = (args[0] as Double).toInt()
            val series = args.drop(1).map { it as List<Double> }
            MathSeriesUtil.topk(k, series)
        }
        "bottomk" -> {
            val k = (args[0] as Double).toInt()
            val series = args.drop(1).map { it as List<Double> }
            MathSeriesUtil.bottomk(k, series)
        }
        "avg_over_time" -> MathSeriesUtil.avgOverTime(
            args[0] as List<Double>, (args[1] as Double).toLong(), (args[2] as Double).toLong()
        )
        "sum_over_time" -> MathSeriesUtil.sumOverTime(
            args[0] as List<Double>, (args[1] as Double).toLong(), (args[2] as Double).toLong()
        )
        "max_over_time" -> MathSeriesUtil.maxOverTime(
            args[0] as List<Double>, (args[1] as Double).toLong(), (args[2] as Double).toLong()
        )
        "min_over_time" -> MathSeriesUtil.minOverTime(
            args[0] as List<Double>, (args[1] as Double).toLong(), (args[2] as Double).toLong()
        )
        "count_over_time" -> MathSeriesUtil.countOverTime(
            args[0] as List<Double>, (args[1] as Double).toLong(), (args[2] as Double).toLong()
        )
        "quantile_over_time" -> MathSeriesUtil.quantileOverTime(
            args[0] as List<Double>, (args[1] as Double).toLong(), (args[2] as Double).toLong(), args[3] as Double
        )
        "stddev_over_time" -> MathSeriesUtil.stddevOverTime(
            args[0] as List<Double>, (args[1] as Double).toLong(), (args[2] as Double).toLong()
        )
        "abs" -> MathSeriesUtil.abs(args[0] as List<Double>)
        "floor" -> MathSeriesUtil.floor(args[0] as List<Double>)
        "ceil" -> MathSeriesUtil.ceil(args[0] as List<Double>)
        "round" -> MathSeriesUtil.round(args[0] as List<Double>)
        "clamp_min" -> MathSeriesUtil.clampMin(args[0] as List<Double>, args[1] as Double)
        "clamp_max" -> MathSeriesUtil.clampMax(args[0] as List<Double>, args[1] as Double)
        "scalar" -> MathSeriesUtil.scalar(args[0] as List<Double>)
        "vector" -> MathSeriesUtil.vector(args[0] as Double)
        "add" -> MathSeriesUtil.add(args[0] as List<Double>, args[1] as List<Double>)
        "subtract" -> MathSeriesUtil.subtract(args[0] as List<Double>, args[1] as List<Double>)
        "multiply" -> MathSeriesUtil.multiply(args[0] as List<Double>, args[1] as List<Double>)
        "divide" -> MathSeriesUtil.divide(args[0] as List<Double>, args[1] as List<Double>)
        "multiply_scalar" -> MathSeriesUtil.multiplyScalar(args[0] as List<Double>, args[1] as Double)
        "add_scalar" -> MathSeriesUtil.addScalar(args[0] as List<Double>, args[1] as Double)
        "greater_than" -> MathSeriesUtil.greaterThan(args[0] as List<Double>, args[1] as Double)
        "less_than" -> MathSeriesUtil.lessThan(args[0] as List<Double>, args[1] as Double)
        "greater_or_equal" -> MathSeriesUtil.greaterOrEqual(args[0] as List<Double>, args[1] as Double)
        "less_or_equal" -> MathSeriesUtil.lessOrEqual(args[0] as List<Double>, args[1] as Double)
        "equal" -> MathSeriesUtil.equal(args[0] as List<Double>, args[1] as Double)
        "histogram_quantile" -> {
            @Suppress("UNCHECKED_CAST")
            val buckets = args[1] as List<Pair<Double, Double>>
            MathSeriesUtil.histogramQuantile(args[0] as Double, buckets)
        }
        else -> error("Unsupported function: $name")
    }
}