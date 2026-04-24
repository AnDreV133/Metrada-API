package com.metrada.util.math

import com.metrada.model.MetricReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Stateless парсер PromQL-подобных запросов
 * Потокобезопасен, можно использовать как синглтон (object)
 */
object ExpressionParser {

    private const val DEFAULT_RANGE = "1m"
    private val DEFAULT_RANGE_SECONDS = parseDurationStatic(DEFAULT_RANGE)

    private fun parseDurationStatic(duration: String): Long {
        var totalSeconds = 0L
        var currentNumber = ""

        for (char in duration) {
            when {
                char.isDigit() || char == '.' -> currentNumber += char
                char == 's' -> {
                    totalSeconds += (currentNumber.toDoubleOrNull() ?: 0.0).toLong()
                    currentNumber = ""
                }
                char == 'm' -> {
                    totalSeconds += ((currentNumber.toDoubleOrNull() ?: 0.0) * 60).toLong()
                    currentNumber = ""
                }
                char == 'h' -> {
                    totalSeconds += ((currentNumber.toDoubleOrNull() ?: 0.0) * 3600).toLong()
                    currentNumber = ""
                }
                char == 'd' -> {
                    totalSeconds += ((currentNumber.toDoubleOrNull() ?: 0.0) * 86400).toLong()
                    currentNumber = ""
                }
                char == 'w' -> {
                    totalSeconds += ((currentNumber.toDoubleOrNull() ?: 0.0) * 604800).toLong()
                    currentNumber = ""
                }
            }
        }
        return totalSeconds
    }

    /**
     * Основной метод: принимает строку запроса и карту доступных рядов
     */
    fun parse(query: String, seriesMap: Map<String, List<Double>>): Any {
        val normalizedQuery = normalizeQuery(query)
        val tokens = tokenize(normalizedQuery)
        val parserState = ParserState(tokens, 0)

        val result = parseExpression(parserState, seriesMap)

        if (parserState.position < parserState.tokens.size) {
            throw IllegalArgumentException(
                "Unexpected tokens at end of query: ${parserState.tokens.subList(parserState.position, parserState.tokens.size)}"
            )
        }

        return result
    }

    /**
     * Извлекает все ссылки на метрики из запроса
     */
    fun extractMetricReferences(query: String): List<MetricReference> {
        val references = mutableSetOf<MetricReference>()
        val normalizedQuery = normalizeQuery(query)
        val cleaned = normalizedQuery.replace(" ", "")

        val fullPattern = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)(?:\{([^}]+)\})?(?:\[(\d+(?:\.\d+)?[smhdw])\])?""")

        fullPattern.findAll(cleaned).forEach { match ->
            val name = match.groupValues[1]
            val tagsString = match.groupValues.getOrNull(2)
            val rangeString = match.groupValues.getOrNull(3)

            val tags = if (!tagsString.isNullOrEmpty()) parseTags(tagsString) else emptyMap()
            val rangeSeconds = rangeString?.let { parseDuration(it) } ?: DEFAULT_RANGE_SECONDS

            if (name !in FUNCTIONS_LIST) {
                references.add(MetricReference(name, tags, rangeSeconds))
            }
        }

        return references.toList()
    }

    /**
     * Извлекает только имена метрик (без тегов и интервалов)
     */
    fun extractMetricNames(query: String): List<String> {
        return extractMetricReferences(query).map { it.name }.distinct()
    }

    fun parseDuration(duration: String): Long = parseDurationStatic(duration)

    // ==================== Приватные методы ====================

    private fun normalizeQuery(query: String): String {
        val cleaned = query.replace(" ", "")
        val metricWithoutRangePattern = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)(?:\{([^}]+)\})?(?![^\[]*\])""")

        var result = cleaned
        var match = metricWithoutRangePattern.find(result)

        while (match != null) {
            val fullMatch = match.value
            val nextCharIndex = match.range.last + 1
            val isFunction = nextCharIndex < result.length && result[nextCharIndex] == '('

            if (!isFunction && fullMatch !in FUNCTIONS_LIST) {
                val replacement = "$fullMatch[$DEFAULT_RANGE]"
                result = result.replaceFirst(fullMatch, replacement)
            }
            match = metricWithoutRangePattern.find(result, match.range.first + fullMatch.length + DEFAULT_RANGE.length + 2)
        }

        return result
    }

    private fun parseTags(tagsString: String): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        val pattern = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)=["']([^"']*)["']""")

        pattern.findAll(tagsString).forEach { match ->
            tags[match.groupValues[1]] = match.groupValues[2]
        }

        return tags
    }

    private fun tokenize(query: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val chars = query.toCharArray()

        while (i < chars.size) {
            val char = chars[i]
            when {
                char.isWhitespace() -> i++
                char == '(' -> {
                    tokens.add(Token(TokenType.LPAREN, "("))
                    i++
                }
                char == ')' -> {
                    tokens.add(Token(TokenType.RPAREN, ")"))
                    i++
                }
                char == ',' -> {
                    tokens.add(Token(TokenType.COMMA, ","))
                    i++
                }
                char == '[' -> {
                    var j = i + 1
                    while (j < chars.size && chars[j] != ']') j++
                    i = j + 1
                }
                char.isLetter() -> {
                    var j = i
                    while (j < chars.size && (chars[j].isLetterOrDigit() || chars[j] == '_')) {
                        j++
                    }
                    tokens.add(Token(TokenType.IDENTIFIER, query.substring(i, j)))
                    i = j
                }
                char.isDigit() || char == '.' -> {
                    var j = i
                    var hasDot = false
                    while (j < chars.size && (chars[j].isDigit() || (chars[j] == '.' && !hasDot))) {
                        if (chars[j] == '.') hasDot = true
                        j++
                    }
                    tokens.add(Token(TokenType.NUMBER, query.substring(i, j).toDouble()))
                    i = j
                }
                else -> i++
            }
        }
        return tokens
    }

    private fun parseExpression(state: ParserState, seriesMap: Map<String, List<Double>>): Any {
        val token = peekToken(state)

        return when (token.type) {
            TokenType.IDENTIFIER -> {
                if (isFunctionCall(state)) {
                    parseFunctionCall(state, seriesMap)
                } else {
                    val metricName = token.value as String
                    consumeToken(state, TokenType.IDENTIFIER)

                    val key = findMatchingKey(metricName, seriesMap)
                    seriesMap[key] ?: error("Series '$metricName' not found. Available keys: ${seriesMap.keys}")
                }
            }
            TokenType.NUMBER -> {
                val number = token.value as Double
                consumeToken(state, TokenType.NUMBER)
                number
            }
            TokenType.LPAREN -> {
                consumeToken(state, TokenType.LPAREN)
                val result = parseExpression(state, seriesMap)
                consumeToken(state, TokenType.RPAREN)
                result
            }
            else -> error("Unexpected token: ${token.type}")
        }
    }

    private fun parseFunctionCall(state: ParserState, seriesMap: Map<String, List<Double>>): Any {
        val functionName = (consumeToken(state, TokenType.IDENTIFIER).value as String).lowercase()
        consumeToken(state, TokenType.LPAREN)

        val args = mutableListOf<Any>()

        while (peekToken(state).type != TokenType.RPAREN) {
            args.add(parseExpression(state, seriesMap))
            if (peekToken(state).type == TokenType.COMMA) {
                consumeToken(state, TokenType.COMMA)
            }
        }

        consumeToken(state, TokenType.RPAREN)
        return executeFunction(functionName, args)
    }

    private fun executeFunction(name: String, args: List<Any>): Any {
        return when (name) {
            "sum" -> {
                val series = extractSeriesList(args)
                MathSeriesUtil.sum(series)
            }
            "avg" -> {
                val series = extractSeriesList(args)
                MathSeriesUtil.avg(series)
            }
            "min" -> {
                val series = extractSeriesList(args)
                MathSeriesUtil.min(series)
            }
            "max" -> {
                val series = extractSeriesList(args)
                MathSeriesUtil.max(series)
            }
            "stddev" -> {
                val series = extractSeriesList(args)
                MathSeriesUtil.stddev(series)
            }
            "count" -> {
                val series = extractSeriesList(args)
                MathSeriesUtil.count(series)
            }
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
            "rate" -> {
                MathSeriesUtil.rate(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong(),
                    (args[2] as Double).toLong()
                )
            }
            "irate" -> {
                MathSeriesUtil.irate(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong()
                )
            }
            "increase" -> {
                MathSeriesUtil.increase(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong(),
                    (args[2] as Double).toLong()
                )
            }
            "delta" -> {
                MathSeriesUtil.delta(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong(),
                    (args[2] as Double).toLong()
                )
            }
            "avg_over_time" -> {
                MathSeriesUtil.avgOverTime(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong(),
                    (args[2] as Double).toLong()
                )
            }
            "sum_over_time" -> {
                MathSeriesUtil.sumOverTime(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong(),
                    (args[2] as Double).toLong()
                )
            }
            "max_over_time" -> {
                MathSeriesUtil.maxOverTime(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong(),
                    (args[2] as Double).toLong()
                )
            }
            "min_over_time" -> {
                MathSeriesUtil.minOverTime(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong(),
                    (args[2] as Double).toLong()
                )
            }
            "count_over_time" -> {
                MathSeriesUtil.countOverTime(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong(),
                    (args[2] as Double).toLong()
                )
            }
            "quantile_over_time" -> {
                MathSeriesUtil.quantileOverTime(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong(),
                    (args[2] as Double).toLong(),
                    args[3] as Double
                )
            }
            "stddev_over_time" -> {
                MathSeriesUtil.stddevOverTime(
                    args[0] as List<Double>,
                    (args[1] as Double).toLong(),
                    (args[2] as Double).toLong()
                )
            }
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

    private fun extractSeriesList(args: List<Any>): List<List<Double>> {
        val result = mutableListOf<List<Double>>()
        for (arg in args) {
            when (arg) {
                is List<*> -> {
                    if (arg.isNotEmpty() && arg.first() is Double) {
                        @Suppress("UNCHECKED_CAST")
                        result.add(arg as List<Double>)
                    } else if (arg.isNotEmpty() && arg.first() is List<*>) {
                        @Suppress("UNCHECKED_CAST")
                        result.addAll(arg as List<List<Double>>)
                    }
                }
            }
        }
        return result
    }

    private fun findMatchingKey(metricName: String, seriesMap: Map<String, List<Double>>): String {
        if (seriesMap.containsKey(metricName)) return metricName

        val withDefaultRange = "$metricName[$DEFAULT_RANGE]"
        if (seriesMap.containsKey(withDefaultRange)) return withDefaultRange

        val matchingKey = seriesMap.keys.find { it == metricName || it.startsWith("$metricName[") }
        return matchingKey ?: metricName
    }

    private fun isFunctionCall(state: ParserState): Boolean {
        val nextIndex = state.position + 1
        return nextIndex < state.tokens.size && state.tokens[nextIndex].type == TokenType.LPAREN
    }

    private fun peekToken(state: ParserState): Token {
        if (state.position >= state.tokens.size) {
            error("Unexpected end of input")
        }
        return state.tokens[state.position]
    }

    private fun consumeToken(state: ParserState, expectedType: TokenType): Token {
        val token = peekToken(state)
        if (token.type != expectedType) {
            error("Expected $expectedType, but got ${token.type}")
        }
        state.position++
        return token
    }

    // ==================== Вспомогательные классы ====================

    private class ParserState(
        val tokens: List<Token>,
        var position: Int
    )

    private enum class TokenType {
        IDENTIFIER, LPAREN, RPAREN, COMMA, NUMBER
    }

    private data class Token(
        val type: TokenType,
        val value: Any,
    )

    private val FUNCTIONS_LIST = setOf(
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
}