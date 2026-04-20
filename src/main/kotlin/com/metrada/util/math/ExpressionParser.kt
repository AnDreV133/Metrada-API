package com.metrada.util.math

import com.metrada.model.MetricReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Парсер PromQL-подобных запросов с поддержкой вложенных функций и интервалов
 */
object ExpressionParser {

    private var currentPosition = 0
    private var tokens = listOf<Token>()

    /**
     * Основной метод: принимает строку запроса и карту доступных рядов
     */
    fun parse(query: String, seriesMap: Map<String, List<Double>>): Any {
        tokens = tokenize(query)
        currentPosition = 0
        val result = parseExpression(seriesMap)

        if (currentPosition < tokens.size) {
            throw IllegalArgumentException(
                "Unexpected tokens at end of query: ${
                    tokens.subList(
                        currentPosition,
                        tokens.size
                    )
                }"
            )
        }

        return result
    }

    /**
     * Извлекает все ссылки на метрики из запроса
     * Поддерживает форматы:
     * - metric_name
     * - metric_name{tag1="value1", tag2="value2"}
     * - metric_name[5m]
     * - metric_name{tag1="value1"}[1h]
     */
    fun extractMetricReferences(query: String): List<MetricReference> {
        val references = mutableSetOf<MetricReference>()
        val cleaned = query.replace(" ", "")

        // Регулярка для полного формата: name{tags}[range]
        val fullPattern = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)(?:\{([^}]+)\})?(?:\[(\d+(?:\.\d+)?[smhdw])\])?""")

        fullPattern.findAll(cleaned).forEach { match ->
            val name = match.groupValues[1]
            val tagsString = match.groupValues.getOrNull(2)
            val rangeString = match.groupValues.getOrNull(3)

            val tags = if (!tagsString.isNullOrEmpty()) parseTags(tagsString) else emptyMap()
            val rangeSeconds = rangeString?.let { parseDuration(it) }

            // Пропускаем ключевые слова функций
            if (name !in setOf(
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
            ) {
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

    /**
     * Парсит duration строку в секунды
     * Поддерживает: 5s, 1m, 2h, 3d, 1w, 1h30m, 2d12h
     */
    fun parseDuration(duration: String): Long {
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

    private fun parseTags(tagsString: String): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        val pattern = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)=["']([^"']*)["']""")

        pattern.findAll(tagsString).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            tags[key] = value
        }

        return tags
    }

    // ==================== Парсинг выражений ====================

    private fun parseExpression(seriesMap: Map<String, List<Double>>): Any {
        val token = peekToken()

        return when (token.type) {
            TokenType.IDENTIFIER -> {
                if (isFunctionCall()) {
                    parseFunctionCall(seriesMap)
                } else {
                    val metricName = token.value as String
                    consumeToken(TokenType.IDENTIFIER)
                    seriesMap[metricName] ?: error("Series '$metricName' not found")
                }
            }

            TokenType.NUMBER -> {
                val number = token.value as Double
                consumeToken(TokenType.NUMBER)
                number
            }

            TokenType.LPAREN -> {
                consumeToken(TokenType.LPAREN)
                val result = parseExpression(seriesMap)
                consumeToken(TokenType.RPAREN)
                result
            }

            else -> error("Unexpected token: ${token.type}")
        }
    }

    private fun parseFunctionCall(seriesMap: Map<String, List<Double>>): Any {
        val functionName = (consumeToken(TokenType.IDENTIFIER).value as String).lowercase()
        consumeToken(TokenType.LPAREN)

        val args = mutableListOf<Any>()

        while (peekToken().type != TokenType.RPAREN) {
            val arg = parseExpression(seriesMap)
            args.add(arg)

            if (peekToken().type == TokenType.COMMA) {
                consumeToken(TokenType.COMMA)
            }
        }

        consumeToken(TokenType.RPAREN)

        return executeFunction(functionName, args)
    }

    // ==================== Выполнение функций ====================

    private fun executeFunction(name: String, args: List<Any>): Any {
        return when (name) {
            // Агрегации
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

            // topk/bottomk
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

            // Rate-функции
            "rate" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                val windowSeconds = (args[2] as Double).toLong()
                MathSeriesUtil.rate(values, stepSeconds, windowSeconds)
            }

            "irate" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                MathSeriesUtil.irate(values, stepSeconds)
            }

            "increase" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                val windowSeconds = (args[2] as Double).toLong()
                MathSeriesUtil.increase(values, stepSeconds, windowSeconds)
            }

            "delta" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                val windowSeconds = (args[2] as Double).toLong()
                MathSeriesUtil.delta(values, stepSeconds, windowSeconds)
            }

            // _over_time функции
            "avg_over_time" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                val windowSeconds = (args[2] as Double).toLong()
                MathSeriesUtil.avgOverTime(values, stepSeconds, windowSeconds)
            }

            "sum_over_time" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                val windowSeconds = (args[2] as Double).toLong()
                MathSeriesUtil.sumOverTime(values, stepSeconds, windowSeconds)
            }

            "max_over_time" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                val windowSeconds = (args[2] as Double).toLong()
                MathSeriesUtil.maxOverTime(values, stepSeconds, windowSeconds)
            }

            "min_over_time" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                val windowSeconds = (args[2] as Double).toLong()
                MathSeriesUtil.minOverTime(values, stepSeconds, windowSeconds)
            }

            "count_over_time" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                val windowSeconds = (args[2] as Double).toLong()
                MathSeriesUtil.countOverTime(values, stepSeconds, windowSeconds)
            }

            "quantile_over_time" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                val windowSeconds = (args[2] as Double).toLong()
                val phi = args[3] as Double
                MathSeriesUtil.quantileOverTime(values, stepSeconds, windowSeconds, phi)
            }

            "stddev_over_time" -> {
                val values = args[0] as List<Double>
                val stepSeconds = (args[1] as Double).toLong()
                val windowSeconds = (args[2] as Double).toLong()
                MathSeriesUtil.stddevOverTime(values, stepSeconds, windowSeconds)
            }

            // Преобразования
            "abs" -> MathSeriesUtil.abs(args[0] as List<Double>)
            "floor" -> MathSeriesUtil.floor(args[0] as List<Double>)
            "ceil" -> MathSeriesUtil.ceil(args[0] as List<Double>)
            "round" -> MathSeriesUtil.round(args[0] as List<Double>)
            "clamp_min" -> MathSeriesUtil.clampMin(args[0] as List<Double>, args[1] as Double)
            "clamp_max" -> MathSeriesUtil.clampMax(args[0] as List<Double>, args[1] as Double)
            "scalar" -> MathSeriesUtil.scalar(args[0] as List<Double>)
            "vector" -> MathSeriesUtil.vector(args[0] as Double)

            // Арифметика
            "add" -> MathSeriesUtil.add(args[0] as List<Double>, args[1] as List<Double>)
            "subtract" -> MathSeriesUtil.subtract(args[0] as List<Double>, args[1] as List<Double>)
            "multiply" -> MathSeriesUtil.multiply(args[0] as List<Double>, args[1] as List<Double>)
            "divide" -> MathSeriesUtil.divide(args[0] as List<Double>, args[1] as List<Double>)
            "multiply_scalar" -> MathSeriesUtil.multiplyScalar(args[0] as List<Double>, args[1] as Double)
            "add_scalar" -> MathSeriesUtil.addScalar(args[0] as List<Double>, args[1] as Double)

            // Логические
            "greater_than" -> MathSeriesUtil.greaterThan(args[0] as List<Double>, args[1] as Double)
            "less_than" -> MathSeriesUtil.lessThan(args[0] as List<Double>, args[1] as Double)
            "greater_or_equal" -> MathSeriesUtil.greaterOrEqual(args[0] as List<Double>, args[1] as Double)
            "less_or_equal" -> MathSeriesUtil.lessOrEqual(args[0] as List<Double>, args[1] as Double)
            "equal" -> MathSeriesUtil.equal(args[0] as List<Double>, args[1] as Double)

            // Хистограмма
            "histogram_quantile" -> {
                val phi = args[0] as Double

                @Suppress("UNCHECKED_CAST")
                val buckets = args[1] as List<Pair<Double, Double>>
                MathSeriesUtil.histogramQuantile(phi, buckets)
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

    private fun isFunctionCall(): Boolean {
        val nextIndex = currentPosition + 1
        return nextIndex < tokens.size && tokens[nextIndex].type == TokenType.LPAREN
    }

    // ==================== Токенизация ====================

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
                    // Пропускаем интервалы в квадратных скобках (они не нужны для парсера)
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

    private fun peekToken(): Token {
        if (currentPosition >= tokens.size) {
            error("Unexpected end of input")
        }
        return tokens[currentPosition]
    }

    private fun consumeToken(expectedType: TokenType): Token {
        val token = peekToken()
        if (token.type != expectedType) {
            error("Expected $expectedType, but got ${token.type}")
        }
        currentPosition++
        return token
    }

    // ==================== Вложенные классы ====================

    private enum class TokenType {
        IDENTIFIER, LPAREN, RPAREN, COMMA, NUMBER
    }

    private data class Token(
        val type: TokenType,
        val value: Any,
    )
}