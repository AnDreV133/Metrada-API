package com.metrada.service.parser

import kotlin.reflect.KClass

// ============================================================
// Part 1: Position, basic types, labels, matchers
// ============================================================



// ============================================================
// Part 2: Tokens
// ============================================================
sealed class Token(open val pos: PosRange) {
    data class Ident(val name: kotlin.String, override val pos: PosRange) : Token(pos)
    data class Number(val value: Double, override val pos: PosRange) : Token(pos)
    data class String(val literal: kotlin.String, override val pos: PosRange) : Token(pos)
    data class Duration(val value: Double, val millis: Long, override val pos: PosRange) : Token(pos)
    data class Op(val type: OpType, override val pos: PosRange) : Token(pos)
    data class LeftBrace(override val pos: PosRange) : Token(pos)
    data class RightBrace(override val pos: PosRange) : Token(PosRange.EMPTY)
    data class LeftParen(override val pos: PosRange) : Token(PosRange.EMPTY)
    data class RightParen(override val pos: PosRange) : Token(pos)
    data class LeftBracket(override val pos: PosRange) : Token(pos)
    data class RightBracket(override val pos: PosRange) : Token(pos)
    data class Comma(override val pos: PosRange) : Token(pos)
    data class By(override val pos: PosRange) : Token(pos)
    data class Without(override val pos: PosRange) : Token(pos)
    data class On(override val pos: PosRange) : Token(pos)
    data class Ignoring(override val pos: PosRange) : Token(pos)
    data class GroupLeft(override val pos: PosRange) : Token(pos)
    data class GroupRight(override val pos: PosRange) : Token(pos)
    data class Offset(override val pos: PosRange) : Token(pos)
    data class Bool(override val pos: PosRange) : Token(pos)
    data class Eof(override val pos: PosRange) : Token(pos)
}

enum class OpType {
    ADD, SUB, MUL, DIV, POW, MOD, EQL, NEQ, GTR, LSS, GTE, LTE, EQLC, EQL_REGEX, NEQ_REGEX,
    LAND, LOR, LUNLESS, ATAN2, TRIM_LOWER, TRIM_UPPER
}

// ============================================================
// Part 3: AST Nodes
// ============================================================

sealed class Node {
    abstract val pos: PosRange
}

sealed class Expr : Node() {
    abstract fun type(): ValueTypeEnum
}

data class NumberLiteral(val value: Double, override val pos: PosRange) : Expr() {
    override fun type() = ValueTypeEnum.Scalar
}

data class StringLiteral(val value: String, override val pos: PosRange) : Expr() {
    override fun type() = ValueTypeEnum.String
}

data class VectorSelector(
    val name: String,
    val labelMatchers: List<LabelMatcher>,
    val originalOffset: Long, // ms
    val timestamp: Long? = null,
    val startOrEnd: StartOrEnd = StartOrEnd.NONE,
    val anchored: Boolean = false,
    val smoothed: Boolean = false,
    override val pos: PosRange,
) : Expr() {
    override fun type() = ValueTypeEnum.Vector
}

enum class StartOrEnd { NONE, START, END }

data class MatrixSelector(
    val vectorSelector: VectorSelector,
    val range: Long, // ms
    override val pos: PosRange,
) : Expr() {
    override fun type() = ValueTypeEnum.Matrix
}

data class SubqueryExpr(
    val expr: Expr,
    val range: Long,
    val step: Long,
    val originalOffset: Long,
    val timestamp: Long? = null,
    val startOrEnd: StartOrEnd = StartOrEnd.NONE,
    override val pos: PosRange,
) : Expr() {
    override fun type() = ValueTypeEnum.Matrix
}

enum class AggrOp {
    SUM, AVG, COUNT, MIN, MAX, STDDEV, STDVAR, QUANTILE, TOPK, BOTTOMK, LIMITK, LIMIT_RATIO, COUNT_VALUES
}

data class AggregateExpr(
    val op: AggrOp,
    val param: Expr?,
    val expr: Expr,
    val grouping: List<String>,
    val without: Boolean,
    override val pos: PosRange,
) : Expr() {
    override fun type() = ValueTypeEnum.Vector
}

data class Call(
    val func: Function,
    val args: List<Expr>,
    override val pos: PosRange,
) : Expr() {
    override fun type() = func.returnType
}

data class Function(
    val name: String,
    val argTypes: List<ValueTypeEnum>,
    val returnType: ValueTypeEnum,
    val variadic: Int = 0,
) {
    companion object {
        val functions = mapOf(
            "rate" to Function("rate", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "increase" to Function("increase", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "sum" to Function("sum", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "avg" to Function("avg", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "count" to Function("count", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "min" to Function("min", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "max" to Function("max", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "stddev" to Function("stddev", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "stdvar" to Function("stdvar", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "topk" to Function("topk", listOf(ValueTypeEnum.Scalar, ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "bottomk" to Function("bottomk", listOf(ValueTypeEnum.Scalar, ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "quantile" to Function(
                "quantile",
                listOf(ValueTypeEnum.Scalar, ValueTypeEnum.Vector),
                ValueTypeEnum.Vector
            ),
            "count_values" to Function(
                "count_values",
                listOf(ValueTypeEnum.String, ValueTypeEnum.Vector),
                ValueTypeEnum.Vector
            ),
            "timestamp" to Function("timestamp", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "abs" to Function("abs", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "ceil" to Function("ceil", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "floor" to Function("floor", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "round" to Function("round", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "exp" to Function("exp", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "ln" to Function("ln", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "log2" to Function("log2", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "log10" to Function("log10", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "sqrt" to Function("sqrt", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "histogram_quantile" to Function(
                "histogram_quantile",
                listOf(ValueTypeEnum.Scalar, ValueTypeEnum.Vector),
                ValueTypeEnum.Vector
            ),
            "label_replace" to Function(
                "label_replace",
                listOf(
                    ValueTypeEnum.Vector,
                    ValueTypeEnum.String,
                    ValueTypeEnum.String,
                    ValueTypeEnum.String,
                    ValueTypeEnum.String
                ),
                ValueTypeEnum.Vector
            ),
            "label_join" to Function(
                "label_join",
                listOf(ValueTypeEnum.Vector, ValueTypeEnum.String, ValueTypeEnum.String, ValueTypeEnum.String),
                ValueTypeEnum.Vector
            ),
            "clamp_max" to Function(
                "clamp_max",
                listOf(ValueTypeEnum.Vector, ValueTypeEnum.Scalar),
                ValueTypeEnum.Vector
            ),
            "clamp_min" to Function(
                "clamp_min",
                listOf(ValueTypeEnum.Vector, ValueTypeEnum.Scalar),
                ValueTypeEnum.Vector
            ),
            "clamp" to Function(
                "clamp",
                listOf(ValueTypeEnum.Vector, ValueTypeEnum.Scalar, ValueTypeEnum.Scalar),
                ValueTypeEnum.Vector
            ),
            "day_of_month" to Function("day_of_month", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "day_of_week" to Function("day_of_week", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "days_in_month" to Function("days_in_month", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "hour" to Function("hour", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "minute" to Function("minute", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "month" to Function("month", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "year" to Function("year", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "time" to Function("time", listOf(), ValueTypeEnum.Scalar),
            "vector" to Function("vector", listOf(ValueTypeEnum.Scalar), ValueTypeEnum.Vector),
            "scalar" to Function("scalar", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Scalar),
            "sort" to Function("sort", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "sort_desc" to Function("sort_desc", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "sort_by_label" to Function(
                "sort_by_label",
                listOf(ValueTypeEnum.Vector, ValueTypeEnum.String),
                ValueTypeEnum.Vector,
                variadic = 1
            ),
            "sort_by_label_desc" to Function(
                "sort_by_label_desc",
                listOf(ValueTypeEnum.Vector, ValueTypeEnum.String),
                ValueTypeEnum.Vector,
                variadic = 1
            ),
            "last_over_time" to Function("last_over_time", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "first_over_time" to Function("first_over_time", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "avg_over_time" to Function("avg_over_time", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "count_over_time" to Function("count_over_time", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "max_over_time" to Function("max_over_time", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "min_over_time" to Function("min_over_time", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "sum_over_time" to Function("sum_over_time", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "stddev_over_time" to Function("stddev_over_time", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "stdvar_over_time" to Function("stdvar_over_time", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "quantile_over_time" to Function(
                "quantile_over_time",
                listOf(ValueTypeEnum.Scalar, ValueTypeEnum.Matrix),
                ValueTypeEnum.Vector
            ),
            "present_over_time" to Function("present_over_time", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "changes" to Function("changes", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "resets" to Function("resets", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "deriv" to Function("deriv", listOf(ValueTypeEnum.Matrix), ValueTypeEnum.Vector),
            "predict_linear" to Function(
                "predict_linear",
                listOf(ValueTypeEnum.Matrix, ValueTypeEnum.Scalar),
                ValueTypeEnum.Vector
            ),
            "holt_winters" to Function(
                "holt_winters",
                listOf(ValueTypeEnum.Matrix, ValueTypeEnum.Scalar, ValueTypeEnum.Scalar),
                ValueTypeEnum.Vector
            ),
            "info" to Function("info", listOf(ValueTypeEnum.Vector), ValueTypeEnum.Vector),
            "start" to Function("start", listOf(), ValueTypeEnum.Scalar),
            "end" to Function("end", listOf(), ValueTypeEnum.Scalar),
            "step" to Function("step", listOf(), ValueTypeEnum.Scalar),
            "range" to Function("range", listOf(), ValueTypeEnum.Scalar)
        )
    }
}

data class BinaryExpr(
    val lhs: Expr,
    val rhs: Expr,
    val op: OpType,
    val vectorMatching: VectorMatching?,
    val returnBool: Boolean,
    override val pos: PosRange,
) : Expr() {
    override fun type(): ValueTypeEnum {
        return when {
            lhs.type() == ValueTypeEnum.Scalar && rhs.type() == ValueTypeEnum.Scalar -> ValueTypeEnum.Scalar
            else -> ValueTypeEnum.Vector
        }
    }
}

data class VectorMatching(
    val card: Card,
    val matchingLabels: List<String>,
    val on: Boolean,
    val include: List<String>,
    val fillValues: Pair<Double?, Double?>,
)

enum class Card { ManyToMany, OneToOne, ManyToOne, OneToMany }

data class ParenExpr(val expr: Expr, override val pos: PosRange) : Expr() {
    override fun type() = expr.type()
}

data class UnaryExpr(val op: OpType, val expr: Expr, override val pos: PosRange) : Expr() {
    override fun type() = expr.type()
}

data class StepInvariantExpr(val expr: Expr, override val pos: PosRange) : Expr() {
    override fun type() = expr.type()
}

// ============================================================
// Part 4: Lexer
// ============================================================

class Lexer(private val input: String) {
    private var pos = 0
    private var start = 0

    fun lex(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            val token = nextToken()
            tokens.add(token)
            if (token is Token.Eof) break
        }
        return tokens
    }

    private fun nextToken(): Token {
        skipWhitespace()
        start = pos
        if (pos >= input.length) return Token.Eof(PosRange(start, pos))
        val ch = input[pos]
        return when {
            ch.isLetter() || ch == '_' || ch == ':' -> readIdentOrKeyword()
            ch.isDigit() || ch == '.' && pos + 1 < input.length && input[pos + 1].isDigit() -> readNumber()
            ch == '"' -> readString()
            ch == '{' -> {
                advance(); Token.LeftBrace(range())
            }

            ch == '}' -> {
                advance(); Token.RightBrace(range())
            }

            ch == '(' -> {
                advance(); Token.LeftParen(range())
            }

            ch == ')' -> {
                advance(); Token.RightParen(range())
            }

            ch == '[' -> {
                advance(); Token.LeftBracket(range())
            }

            ch == ']' -> {
                advance(); Token.RightBracket(range())
            }

            ch == ',' -> {
                advance(); Token.Comma(range())
            }

            ch == '+' -> {
                advance(); Token.Op(OpType.ADD, range())
            }

            ch == '-' -> {
                advance(); Token.Op(OpType.SUB, range())
            }

            ch == '*' -> {
                advance(); Token.Op(OpType.MUL, range())
            }

            ch == '/' -> {
                advance(); Token.Op(OpType.DIV, range())
            }

            ch == '^' -> {
                advance(); Token.Op(OpType.POW, range())
            }

            ch == '%' -> {
                advance(); Token.Op(OpType.MOD, range())
            }

            ch == '=' -> {
                if (peek() == '=') {
                    advance(); advance(); Token.Op(OpType.EQL, range())
                } else if (peek() == '~') {
                    advance(); advance(); Token.Op(OpType.EQL_REGEX, range())
                } else {
                    advance(); Token.Op(OpType.EQLC, range())
                }
            }

            ch == '!' -> {
                if (peek() == '=') {
                    advance(); advance(); Token.Op(OpType.NEQ, range())
                } else if (peek() == '~') {
                    advance(); advance(); Token.Op(OpType.NEQ_REGEX, range())
                } else throw IllegalArgumentException("unexpected !")
            }

            ch == '>' -> {
                if (peek() == '=') {
                    advance(); advance(); Token.Op(OpType.GTE, range())
                } else {
                    advance(); Token.Op(OpType.GTR, range())
                }
            }

            ch == '<' -> {
                if (peek() == '=') {
                    advance(); advance(); Token.Op(OpType.LTE, range())
                } else {
                    advance(); Token.Op(OpType.LSS, range())
                }
            }

            else -> throw IllegalArgumentException("unexpected character $ch at position $pos")
        }
    }

    private fun readIdentOrKeyword(): Token {
        while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_' || input[pos] == ':')) pos++
        val keyword = when (val text = input.substring(start, pos)) {
            "by" -> Token.By(range())
            "without" -> Token.Without(range())
            "on" -> Token.On(range())
            "ignoring" -> Token.Ignoring(range())
            "group_left" -> Token.GroupLeft(range())
            "group_right" -> Token.GroupRight(range())
            "offset" -> Token.Offset(range())
            "bool" -> Token.Bool(range())
            "and" -> Token.Op(OpType.LAND, range())
            "or" -> Token.Op(OpType.LOR, range())
            "unless" -> Token.Op(OpType.LUNLESS, range())
            else -> Token.Ident(text, range())
        }
        return keyword
    }

    private fun readNumber(): Token {
        var isFloat = false
        while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
            if (input[pos] == '.') isFloat = true
            pos++
        }
        if (pos < input.length && input[pos] in setOf('s', 'm', 'h', 'd', 'w', 'y')) {
            val unit = input[pos]
            pos++
            val numStr = input.substring(start, pos - 1)
            val value = numStr.toDoubleOrNull() ?: throw IllegalArgumentException("invalid duration: $numStr")
            val millis = when (unit) {
                's' -> (value * 1000).toLong()
                'm' -> (value * 60 * 1000).toLong()
                'h' -> (value * 60 * 60 * 1000).toLong()
                'd' -> (value * 24 * 60 * 60 * 1000).toLong()
                'w' -> (value * 7 * 24 * 60 * 60 * 1000).toLong()
                'y' -> (value * 365 * 24 * 60 * 60 * 1000).toLong()
                else -> 0L
            }
            return Token.Duration(value, millis, range())
        }
        val numStr = input.substring(start, pos)
        val value = numStr.toDoubleOrNull() ?: throw IllegalArgumentException("invalid number: $numStr")
        return Token.Number(value, range())
    }

    private fun readString(): Token {
        pos++ // skip opening "
        val sb = StringBuilder()
        while (pos < input.length && input[pos] != '"') {
            if (input[pos] == '\\') {
                pos++
                if (pos >= input.length) throw IllegalArgumentException("unterminated escape in string")
                when (val esc = input[pos]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    else -> sb.append(esc)
                }
            } else {
                sb.append(input[pos])
            }
            pos++
        }
        if (pos >= input.length) throw IllegalArgumentException("unterminated string")
        pos++ // closing "
        return Token.String(sb.toString(), range())
    }

    private fun skipWhitespace() {
        while (pos < input.length && input[pos].isWhitespace()) pos++
    }

    private fun advance() {
        pos++
    }

    private fun peek(): Char = if (pos + 1 < input.length) input[pos + 1] else Char.MIN_VALUE
    private fun range(): PosRange = PosRange(start, pos)
}

// ============================================================
// Part 5: Parser (recursive descent)
// ============================================================

class PromQLParser(private val tokens: List<Token>) {
    private var pos = 0

    companion object {
        fun parse(input: String): Expr {
            val lexer = Lexer(input)
            val tokens = lexer.lex()
            val parser = PromQLParser(tokens)
            return parser.parseExpr()
        }
    }

    fun parseExpr(): Expr {
        val expr = parseBinaryExpr(0)
        if (!peek(Token.Eof::class)) {
            error("unexpected token at end: ${current()}")
        }
        return expr
    }

    private fun parseBinaryExpr(minPrecedence: Int): Expr {
        var lhs = parseUnaryExpr()
        while (true) {
            val opTok = peekOrNull()
            if (opTok !is Token.Op) break
            val precedence = opPrecedence(opTok.type)
            if (precedence < minPrecedence) break
            consume()
            var rhs = parseBinaryExpr(precedence + 1)
            val matching = if (opTok.type in setOf(
                    OpType.ADD, OpType.SUB, OpType.MUL, OpType.DIV, OpType.POW, OpType.MOD,
                    OpType.EQLC, OpType.NEQ, OpType.GTR, OpType.LSS, OpType.GTE, OpType.LTE,
                    OpType.LAND, OpType.LOR, OpType.LUNLESS
                )
            ) {
                parseVectorMatching()
            } else null
            val returnBool = if (peek(Token.Bool::class)) {
                consume(); true
            } else false
            lhs = BinaryExpr(lhs, rhs, opTok.type, matching, returnBool, PosRange(lhs.pos.start, rhs.pos.end))
        }
        return lhs
    }

    private fun parseUnaryExpr(): Expr {
        if (peek(Token.Op::class)) {
            val opTok = current() as Token.Op
            if (opTok.type == OpType.ADD || opTok.type == OpType.SUB) {
                consume()
                val expr = parseUnaryExpr()
                return UnaryExpr(opTok.type, expr, PosRange(opTok.pos.start, expr.pos.end))
            }
        }
        return parsePrimaryExpr()
    }

    private fun parsePrimaryExpr(): Expr {
        return when (val tok = consume()) {
            is Token.Number -> NumberLiteral(tok.value, tok.pos)
            is Token.String -> StringLiteral(tok.literal, tok.pos)
            is Token.Ident -> {
                if (peek(Token.LeftParen::class)) {
                    parseCall(tok.name)
                } else {
                    parseVectorSelector(tok.name)
                }
            }

            is Token.LeftParen -> {
                val expr = parseBinaryExpr(0)
                expect(Token.RightParen::class)
                ParenExpr(expr, PosRange(tok.pos.start, prevPos()))
            }

            else -> error("unexpected token: $tok")
        }
    }

    private fun parseCall(name: String): Call {
        val lparen = expect(Token.LeftParen::class)
        val args = mutableListOf<Expr>()
        if (!peek(Token.RightParen::class)) {
            do {
                args.add(parseBinaryExpr(0))
            } while (peek(Token.Comma::class).also { if (it) consume() })
        }
        val rparen = expect(Token.RightParen::class)
        val func = Function.functions[name] ?: error("unknown function: $name")
        return Call(func, args, PosRange(lparen.pos.start, rparen.pos.end))
    }

    private fun parseVectorSelector(name: String): VectorSelector {
        var labelMatchers = emptyList<LabelMatcher>()
        if (peek(Token.LeftBrace::class)) {
            consume()
            labelMatchers = parseLabelMatchers()
            expect(Token.RightBrace::class)
        }
//        else if (name == null) {
//            error("vector selector must have either metric name or label matchers")
//        }
        var offset = 0L
        var timestamp: Long? = null
        var startOrEnd = StartOrEnd.NONE
        var anchored = false
        var smoothed = false

        while (true) {
            when (val tok = peekOrNull()) {
                is Token.Offset -> {
                    consume()
                    val dur = expect(Token.Duration::class) as Token.Duration
                    offset = dur.millis
                }

                is Token.Op -> {
                    if ((tok as Token.Op).type == OpType.EQL && peekNext() is Token.Number) {
                        consume() // consume '@'
                        val num = expect(Token.Number::class) as Token.Number
                        timestamp = (num.value * 1000).toLong()
                    } else break
                }

                is Token.Ident -> {
                    when ((tok as Token.Ident).name) {
                        "start" -> {
                            consume(); startOrEnd = StartOrEnd.START
                        }

                        "end" -> {
                            consume(); startOrEnd = StartOrEnd.END
                        }

                        "anchored" -> {
                            consume(); anchored = true
                        }

                        "smoothed" -> {
                            consume(); smoothed = true
                        }

                        else -> break
                    }
                }

                else -> break
            }
        }
        return VectorSelector(
            name,
            labelMatchers,
            offset,
            timestamp,
            startOrEnd,
            anchored,
            smoothed,
            PosRange(startPos(), prevPos())
        )
    }

    private fun parseLabelMatchers(): List<LabelMatcher> {
        return mutableListOf<LabelMatcher>().apply {
            do {
                val label = expect(Token.Ident::class) as Token.Ident
                val opTok = when (val t = consume()) {
                    is Token.Op -> t
                    else -> error("expected operator after label name")
                }
                val value = expect(Token.String::class) as Token.String
                val matchType = when (opTok.type) {
                    OpType.EQLC -> MatchTypeEnum.Equal
                    OpType.NEQ -> MatchTypeEnum.NotEqual
                    OpType.EQL_REGEX -> MatchTypeEnum.Regexp
                    OpType.NEQ_REGEX -> MatchTypeEnum.NotRegexp
                    else -> error("invalid label matching operator: ${opTok.type}")
                }
                add(LabelMatcher(matchType, label.name, value.literal))
            } while (peek(Token.Comma::class).also { if (it) consume() })
        }
    }

    private fun parseVectorMatching(): VectorMatching? {
        if (peek(Token.Ignoring::class)) {
            consume()
            val labels = parseLabelList()
            return VectorMatching(Card.ManyToMany, labels, false, emptyList(), null to null)
        }
        if (peek(Token.On::class)) {
            consume()
            val labels = parseLabelList()
            var card = Card.ManyToMany
            var include = emptyList<String>()
            if (peek(Token.GroupLeft::class)) {
                consume()
                include = parseLabelList()
                card = Card.ManyToOne
            } else if (peek(Token.GroupRight::class)) {
                consume()
                include = parseLabelList()
                card = Card.OneToMany
            }
            return VectorMatching(card, labels, true, include, null to null)
        }
        return null
    }

    private fun parseLabelList(): List<String> {
        expect(Token.LeftParen::class)
        val list = mutableListOf<String>()
        if (!peek(Token.RightParen::class)) {
            do {
                val ident = expect(Token.Ident::class) as Token.Ident
                list.add(ident.name)
            } while (peek(Token.Comma::class).also { if (it) consume() })
        }
        expect(Token.RightParen::class)
        return list
    }

    private fun opPrecedence(op: OpType): Int = when (op) {
        OpType.LAND, OpType.LOR, OpType.LUNLESS -> 4
        OpType.EQLC, OpType.NEQ, OpType.GTR, OpType.LSS, OpType.GTE, OpType.LTE -> 3
        OpType.ADD, OpType.SUB -> 2
        OpType.MUL, OpType.DIV, OpType.MOD -> 1
        OpType.POW -> 0
        else -> 5
    }

    private fun peek(tokenClass: KClass<out Token>): Boolean = peekOrNull()?.javaClass == tokenClass.java
    private fun peekOrNull(): Token? = if (pos < tokens.size) tokens[pos] else null
    private fun peekNext(): Token? = if (pos + 1 < tokens.size) tokens[pos + 1] else null
    private fun current(): Token = tokens[pos]
    private fun consume(): Token = tokens[pos++]
    private fun expect(tokenClass: KClass<out Token>): Token {
        val tok = consume()
        if (tok.javaClass != tokenClass.java) {
            error("expected ${tokenClass.simpleName} but got $tok")
        }
        return tok
    }

    private fun startPos(): Int = if (pos < tokens.size) tokens[pos].pos.start else 0
    private fun prevPos(): Int = if (pos > 0) tokens[pos - 1].pos.end else 0
}

// ============================================================
// Part 6: Public API entry point
// ============================================================

fun parsePromQL(input: String): Expr = PromQLParser.parse(input)