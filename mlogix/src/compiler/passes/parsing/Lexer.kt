package mlogix.compiler.passes.parsing

import arc.func.Boolf
import arc.graphics.Color
import arc.graphics.Colors
import arc.struct.Seq
import mlogix.compiler.core.SourceMap.SourceFile
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.diagnostic.Diagnostic.LexerDiag
import mlogix.util.I18N.bundle
import java.util.Locale.getDefault
import kotlin.math.min

/**
 * 一个项目 构造一次
 */
class Lexer(private val problems: DiagHandler) {

    private lateinit var sourceFile: SourceFile

    private var length: Int = 0
    private var start: Int = 0
    private var current: Int = 0

    private var isPrevNewline: Boolean = false

    private val recoverTerminators: Set<Char> = setOf(
        ' ', '\n', '\r', ':', ';', ',', '.', '(', ')', '[', ']', '{', '}'
    )

    /**
     * 一个文件 重置一次
     * 重置后才能调用其他方法
     */
    fun reset(sourceFile: SourceFile) {
        this.sourceFile = sourceFile
        this.length = sourceFile.length()

        this.isPrevNewline = false

        this.start = 0
        this.current = 0
    }

    /**
     * 解析独立文本为token序列
     * 运行前会重置problemCollector
     */
    fun tokenize(source: String): Seq<Token> {
        problems.clear()

        val sourceFile = SourceFile(source)
        reset(sourceFile)
        val tokens = Seq<Token>()
        while (true) {
            val token = scanToken()
            if (token.type != TokenType.EOF) {
                tokens.add(token)
            } else {
                break
            }
        }
        return tokens
    }

    /**
     * 扫描下一个Token
     */
    fun scanToken(): Token {
        while (true) {
            start = current

            if (this.isAtEnd) return eofToken()
            if (isPrevNewline) {
                isPrevNewline = false
                recover { c: Char? -> c != '\n' } // 跳过newline防止重复出现
                if (this.isAtEnd) return eofToken()
                start = current
            }

            when (val c = advance()) {
                '+' -> return if (match('+')) {
                    token(TokenType.PLUS_PLUS)
                } else if (match('=')) {
                    token(TokenType.PLUS_ASSIGN)
                } else {
                    token(TokenType.PLUS)
                }


                '-' -> return if (match('-')) {
                    token(TokenType.MINUS_MINUS)
                } else if (match('=')) {
                    token(TokenType.MINUS_ASSIGN)
                } else if (match('>')) {
                    token(TokenType.ARROW)
                } else {
                    token(TokenType.MINUS)
                }

                '*' -> return if (match('*')) {
                    if (match('=')) {
                        token(TokenType.STAR_STAR_ASSIGN)
                    } else {
                        token(TokenType.STAR_STAR)
                    }
                } else if (match('=')) {
                    token(TokenType.STAR_ASSIGN)
                } else {
                    token(TokenType.STAR)
                }

                '/' -> return if (match('/')) {
                    if (match('=')) {
                        token(TokenType.SLASH_SLASH_ASSIGN)
                    } else {
                        token(TokenType.SLASH_SLASH)
                    }
                } else if (match('=')) {
                    token(TokenType.SLASH_ASSIGN)
                } else {
                    token(TokenType.SLASH)
                }

                '^' -> return if (match('=')) {
                    token(TokenType.CARET_ASSIGN)
                } else {
                    token(TokenType.CARET)
                }

                '%' -> return if (match('%')) {
                    if (match('=')) {
                        token(TokenType.PERCENT_PERCENT_ASSIGN)
                    } else {
                        token(TokenType.PERCENT_PERCENT)
                    }
                } else if (match('=')) {
                    token(TokenType.PERCENT_ASSIGN)
                } else {
                    token(TokenType.PERCENT)
                }

                '&' -> return if (match('&')) {
                    token(TokenType.AND_AND)
                } else if (match('=')) {
                    token(TokenType.AND_ASSIGN)
                } else {
                    token(TokenType.AND)
                }

                '|' -> return if (match('|')) {
                    token(TokenType.OR_OR)
                } else if (match('=')) {
                    token(TokenType.OR_ASSIGN)
                } else {
                    token(TokenType.OR)
                }

                '~' -> return token(TokenType.TILDE)

                '!' -> return if (match('=')) {
                    if (match('=')) {
                        token(TokenType.BANG_EQ_EQ)
                    } else {
                        token(TokenType.BANG_EQ)
                    }
                } else {
                    token(TokenType.BANG)
                }

                '！' -> {
                    reportConfusedChar('！', start)
                    return if (match('=')) {
                        if (match('=')) {
                            token(TokenType.BANG_EQ_EQ)
                        } else {
                            token(TokenType.BANG_EQ)
                        }
                    } else {
                        token(TokenType.BANG)
                    }
                }

                '<' -> return if (match('<')) {
                    if (match('=')) {
                        token(TokenType.SHL_ASSIGN)
                    } else {
                        token(TokenType.SHL)
                    }
                } else if (match('=')) {
                    token(TokenType.LESS_EQ)
                } else {
                    token(TokenType.LESS)
                }

                '>' -> return if (match('>')) {
                    if (match('>')) {
                        if (match('=')) {
                            token(TokenType.SHR_ASSIGN)
                        } else {
                            token(TokenType.SHR)
                        }
                    } else {
                        if (match('=')) {
                            token(TokenType.SAR_ASSIGN)
                        } else {
                            token(TokenType.SAR)
                        }
                    }
                } else if (match('=')) {
                    token(TokenType.GREATER_EQ)
                } else {
                    token(TokenType.GREATER)
                }

                '=' -> return if (match('=')) {
                    if (match('=')) {
                        token(TokenType.EQ_EQ_EQ)
                    } else {
                        token(TokenType.EQ_EQ)
                    }
                } else {
                    token(TokenType.ASSIGN)
                }

                '.' -> return token(TokenType.DOT)

                '。' -> {
                    reportConfusedChar('。', start)
                    return token(TokenType.DOT)
                }

                ':' -> return if (match('<')) {
                    token(TokenType.COLON_LESS)
                } else if (match('=')) {
                    token(TokenType.COLON_ASSIGN)
                } else {
                    token(TokenType.COLON)
                }

                '：' -> {
                    reportConfusedChar('：', start)
                    return if (match('<')) {
                        token(TokenType.COLON_LESS)
                    } else if (match('=')) {
                        token(TokenType.COLON_ASSIGN)
                    } else {
                        token(TokenType.COLON)
                    }
                }

                ';' -> return token(TokenType.SEMICOLON)

                '；' -> {
                    reportConfusedChar('；', start)
                    return token(TokenType.SEMICOLON)
                }

                ',' -> return token(TokenType.COMMA)

                '，' -> {
                    reportConfusedChar('，', start)
                    return token(TokenType.COMMA)
                }

                '(' -> return token(TokenType.LPAREN)

                '（' -> {
                    reportConfusedChar('（', start)
                    return token(TokenType.LPAREN)
                }

                ')' -> return token(TokenType.RPAREN)

                '）' -> {
                    reportConfusedChar('）', start)
                    return token(TokenType.RPAREN)
                }

                '[' -> return token(TokenType.LBRACKET)

                '【' -> {
                    reportConfusedChar('【', start)
                    return token(TokenType.LBRACKET)
                }

                ']' -> return token(TokenType.RBRACKET)

                '】' -> {
                    reportConfusedChar('】', start)
                    return token(TokenType.RBRACKET)
                }

                '{' -> return token(TokenType.LBRACE)
                '}' -> return token(TokenType.RBRACE)
                '?' -> return token(TokenType.QUESTION_MARK)

                '？' -> {
                    reportConfusedChar('？', start)
                    return token(TokenType.QUESTION_MARK)
                }

                '"' -> return string()

                '“' -> {
                    reportConfusedChar('“', start)
                    return string()
                }

                '”' -> {
                    reportConfusedChar('”', start)
                    return string()
                }

                '#' -> {
                    val r = comment()
                    if (r != null) {
                        return r
                    } else {
                        continue
                    }
                }

                '\r' -> {
                    isPrevNewline = true
                    match('\n')
                    return token(TokenType.NEWLINE)
                }

                '\n' -> {
                    isPrevNewline = true
                    return token(TokenType.NEWLINE)
                }

                ' ', '\t', '\uFEFF' -> continue

                '\u0000' -> return token(TokenType.EOF)

                else -> if (isDigit(c)) {
                    return number()
                } else if (isIdentifierStart(c)) {
                    return identifier()
                } else {
                    error(bundle.get("diag.unregistered-char"))
                        .label(
                            span(start, start + 1),
                            "0x${Integer.toHexString(c.code).uppercase(getDefault())}"
                        )
                    recover { ch: Char -> recoverTerminators.contains(ch) }
                    return token(TokenType.ERROR, subString(start, current))
                }
            }
        }
    }

    /* 标识符 关键字 */
    private fun identifier(): Token {
        while (!this.isAtEnd && isIdentifierPart(peek())) advance()
        val text = subString(start, current)
        if (text.startsWith("__")) {
            error(bundle.get("diag.invalid-identifier")).apply {
                label(span(start, start + 2))
                help(bundle.get("diag.invalid-identifier.help"))
                    .delete(span(start, start + 1))
            }
            return token(TokenType.IDENTIFIER, text.substring(1))
        }
        val type = TokenType.KEYWORDS_MAP[text]
        return if (type == null) {
            token(TokenType.IDENTIFIER, text)
        } else {
            token(type)
        }
    }

    private fun string(): Token {
        while (!match('"')) {
            if (this.isAtEnd || check('\n')) {
                error(bundle.format("diag.unclosed-string", "\""))
                    .label(span(current, current + 1))
                    .label(span(start, start + 1), bundle.get("diag.unclosed-string.start"))
                return token(TokenType.STR, subString(start + 1, current))
            }
            if (check('”')) {
                reportConfusedChar('”', current)
                advance()
                break
            }
            advance()
        }
        return token(TokenType.STR, subString(start + 1, current - 1))
    }

    private fun number(): Token {
        if (match('x')) {
            // 16进制整数 _分隔符
            if (peek(-2) != '0') {
                error(bundle.get("diag.invalid-hex-prefix"))
                    .label(span(start, start + 2), "")
                recover { c: Char? -> !isDigit(c!!) && !isAlpha(c) }
                return token(TokenType.ERROR)
            }
            val builder = StringBuilder()
            while (!this.isAtEnd) {
                if (isHexDigit(peek())) {
                    builder.append(peek())
                } else if (check('_')) {
                    // 忽略数字分隔符
                } else if (isAlpha(peek())) {
                    // ['g' ~ 'z'] | ['G' ~ 'Z']
                    error(bundle.get("diag.invalid-hex-char"))
                        .label(span(current, current + 1), Integer.toHexString(peek().code))
                    recover { c: Char? -> !isDigit(c!!) && !isAlpha(c) }
                    return token(TokenType.ERROR)
                } else {
                    break
                }
                advance()
            }
            return token(TokenType.INT, builder.toString().toLong(16).toDouble())

        } else if (match('b')) {
            // 2进制整数 _分隔符
            if (peek(-2) != '0') {
                error(bundle.get("diag.invalid-bin-prefix"))
                    .label(span(start, start + 2), "")
                recover { c: Char? -> !isDigit(c!!) && !isAlpha(c) }
                return token(TokenType.ERROR)
            }
            val builder = StringBuilder()
            while (!this.isAtEnd) {
                if (isBinDigit(peek())) {
                    builder.append(peek())
                } else if (check('_')) {
                    // 忽略数字分隔符
                } else if (isAlpha(peek())) {
                    error(bundle.get("diag.invalid-bin-char"))
                        .label(span(current, current + 1), Integer.toHexString(peek().code))
                    recover { c: Char? -> !isDigit(c!!) && !isAlpha(c) }
                    return token(TokenType.ERROR)
                } else {
                    break
                }
                advance()
            }
            return token(TokenType.INT, builder.toString().toLong(2).toDouble())


        } else if (peek(-1) == '0' && match('%')) {
            // 颜色值
            return color()

        } else {
            // 普通浮点数 科学计数法 _分隔符
            var isInt = true

            val builder = StringBuilder()
            builder.append(peek(-1))

            while (!this.isAtEnd && peek() == '_') advance() //防止normalNumber报错

            numberFragment(builder)

            if (match('.')) {
                isInt = false

                builder.append('.')
                numberFragment(builder)
            }

            if (match('e') || match('E')) {
                isInt = false

                builder.append('e')
                if (match('+')) {
                    builder.append('+')
                } else if (match('-')) {
                    builder.append('-')
                }

                numberFragment(builder)

                if (match('.')) {
                    builder.append('.')
                    numberFragment(builder)
                }
            }

            return token(if (isInt) TokenType.INT else TokenType.NUM, builder.toString().toDouble())
        }
    }

    /* 给number()用的，扫描下一段最简单的数字片段，123_456，不允许两侧分隔符 */
    private fun numberFragment(builder: StringBuilder) {
        if (this.isAtEnd) return
        val c = peek()
        if (c == '_') {
            error(bundle.get("diag.invalid-num-separator"))
                .label(span(current, current + 1), Integer.toHexString(c.code))
            advance()
        }

        while (!this.isAtEnd) {
            if (isDigit(c)) {
                builder.append(c)
            } else if (c == '_') {
                //忽略分隔符
            } else if (c == 'e' || c == 'E') {
                break
            } else if (isAlpha(c)) {
                error(bundle.get("diag.unexpected-num-char"))
                    .label(span(current, current + 1), Integer.toHexString(c.code))
                recover { c: Char -> !isDigit(c) && !isAlpha(c) }
                break
            } else {
                break
            }
            advance()
        }

        if (builder[builder.length - 1] == '_') {
            error(bundle.get("diag.invalid-num-separator"))
                .label(span(current, current + 1), Integer.toHexString(c.code))
        }
    }

    /**
     * 颜色值
     */
    private fun color(): Token {
        var notColorName = false
        var notColorValue = false
        val text = buildString {
            while (!this@Lexer.isAtEnd) {
                when (val c = peek()) {
                    '_' -> append(c)

                    in '0'..'9' -> {
                        notColorName = true
                        append(c)
                    }

                    in 'a'..'f' -> append(c)
                    in 'A'..'F' -> append(c)

                    in 'g'..'z' -> {
                        notColorValue = true
                        append(c)
                    }

                    in 'G'..'Z' -> {
                        notColorValue = true
                        append(c)
                    }

                    else -> break
                }
                advance()
            }
        }

        if (!notColorName) {
            Colors.get(text)?.also {
                return token(TokenType.COL, Color.toDoubleBits(it.r, it.g, it.b, it.a))
            }
            var attempt = text.lowercase().filterNot { it == '_' }
            Colors.get(attempt)?.also {
                error(bundle.format("diag.unknown-color-name", text)).apply {
                    label(span(start, current))
                    help(bundle.format("diag.unknown-color-name.help", attempt))
                }
                return token(TokenType.COL, Color.toDoubleBits(it.r, it.g, it.b, it.a))
            }
            attempt = text.uppercase()
            Colors.get(attempt)?.also {
                error(bundle.format("diag.unknown-color-name", text)).apply {
                    label(span(start, current))
                    help(bundle.format("diag.unknown-color-name.help", attempt))
                }
                return token(TokenType.COL, Color.toDoubleBits(it.r, it.g, it.b, it.a))
            }
        }
        if (!notColorValue) {
            when (text.replace("_", "").length) {
                6 -> { // 0cRR_GG_BB
                    val r = text.substring(0, 2).toInt(16)
                    val g = text.substring(2, 4).toInt(16)
                    val b = text.substring(4, 6).toInt(16)
                    val a = 0xFF
                    return token(TokenType.COL, Color.toDoubleBits(r, g, b, a))
                }

                8 -> { // 0cRR_GG_BB_AA
                    val r = text.substring(0, 2).toInt(16)
                    val g = text.substring(2, 4).toInt(16)
                    val b = text.substring(4, 6).toInt(16)
                    val a = text.substring(6, 8).toInt(16)
                    return token(TokenType.COL, Color.toDoubleBits(r, g, b, a))
                }

                else -> {
                    error(bundle.format("diag.invalid-color-len", text.length))
                        .label(span(start + 1, current))
                    return token(TokenType.ERROR)
                }
            }
        }

        error(bundle.get("diag.invalid-color"))
            .label(span(start, current), "")
        return token(TokenType.ERROR)
    }


    private fun comment(): Token? {
        val docComment = StringBuilder()

        if (match('/')) { // 多行文档注释开始 #/
            while (!this.isAtEnd) {
                if (match("/#")) { // 多行文档注释结束 #/ ... /#
                    return token(TokenType.DOC_COMMENT, docComment.toString())
                }
                docComment.append(advance())
            }
            // return token(TokenType.DOC_COMMENT, docComment.toString())
            // TODO 实装docComment
            return null
        } else if (match('|')) { // 文档注释开始 #|
            // int col = sourceFile.getCol(current - 1) - 1;
            while (!this.isAtEnd) {
                if (match('\n')) {
                    while (!this.isAtEnd && isWhitespace(peek())) {
                        advance()
                    }
                    if (!match('|')) { // 文档注释结束 #| ...\n |
                        return token(TokenType.DOC_COMMENT, docComment.toString())
                    }
                    docComment.append('\n')
                }
                docComment.append(advance())
            }
            // return token(TokenType.DOC_COMMENT, docComment.toString())
            // TODO 实装docComment
            return null
        } else if (match('*')) { // 多行注释开始 #*
            while (!this.isAtEnd && !match("*#")) { // #* ... *#
                advance()
            }
            return null
        } else { // 单行注释 # ...
            while (!this.isAtEnd && !match('\n')) {
                advance()
            }
            return null
        }
    }

    private fun reportConfusedChar(char: Char, index: Int) {
        val value = confusedChars[char]
        if (value != null) {
            error(bundle.format("diag.use-fullwidth-char", char.toString())).apply {
                val char = span(index, index + 1)
                label(char)
                help(bundle.format("diag.use-fullwidth-char.help", value.toString()))
                    .replace(char, value.toString())
            }
        }
    }

    private fun isAlpha(c: Char): Boolean {
        return (c in 'a'..'z')
                || (c in 'A'..'Z')
                || (c == '_')
    }

    private fun isDigit(c: Char): Boolean {
        return c in '0'..'9'
    }

    private fun isHexDigit(c: Char): Boolean {
        return isDigit(c)
                || (c in 'a'..'f')
                || (c in 'A'..'F')
    }

    private fun isBinDigit(c: Char): Boolean {
        return c == '0' || c == '1'
    }

    // 标识符开头
    private fun isIdentifierStart(c: Char): Boolean {
        return isAlpha(c)
                || Character.isLetter(c)
    }

    // 标识符中间和末尾
    private fun isIdentifierPart(c: Char): Boolean {
        return isIdentifierStart(c) || isDigit(c)
    }

    private fun isWhitespace(c: Char): Boolean {
        return Character.isWhitespace(c)
    }

    private fun charAt(index: Int): Char {
        return sourceFile.charAt(index)
    }

    private fun match(expected: Char): Boolean {
        if (this.isAtEnd) return false
        if (charAt(current) != expected) return false
        current++
        return true
    }

    /* 检查当前往后的字符串是否是期望字符串，是则推进至该字符串后1 */
    private fun match(expected: String): Boolean {
        if (this.isAtEnd) return false
        if (expected == subString(current, min(current + expected.length, length))) {
            advance(expected.length)
            return true
        }
        return false
    }


    private fun check(expected: Char): Boolean {
        return !this.isAtEnd && charAt(current) == expected
    }

    private val isAtEnd: Boolean
        get() = current >= length

    private fun advance(): Char {
        current++
        return charAt(current - 1)
    }

    private fun advance(step: Int): Char {
        current += step
        return charAt(current - step)
    }

    private fun peek(): Char {
        return charAt(current)
    }

    private fun peek(step: Int): Char {
        return charAt(current + step)
    }

    private fun subString(start: Int, end: Int): String {
        return sourceFile.subString(start, end)
    }

    private fun token(type: TokenType, literal: Any? = null): Token {
        val span = Span.between(sourceFile.index, start, current)

//        if (Log.isAllowed(Log.LogType.DEBUG)) {
//            val lineAndCol: IntArray? = sourceFile.getLineAndCol(start)
//            Log.debug(
//                start.toString() + Ansi.CYAN + "┃"
//                        + Ansi.DEFAULT + "(" + lineAndCol!![0] + "," + lineAndCol[1] + ")" + Ansi.CYAN + "┃"
//                        + Ansi.DEFAULT + type.toString() + Ansi.CYAN + "┃"
//                        + Ansi.DEFAULT + sourceFile.subString(
//                    start,
//                    current
//                ) + (if (literal == null) "" else (Ansi.CYAN + "┃"
//                        + Ansi.DEFAULT + literal))
//            )
//        }

        return Token(span, type, literal)
    }

    // EOF特化
    private fun eofToken(): Token {
        // 特化部分:current -> start
        val span = Span.between(sourceFile.index, start, current)

//        if (Log.isAllowed(Log.LogType.DEBUG)) {
//            val lineAndCol = sourceFile.getLineAndCol(start)
//            Log.debug(
//                (start.toString() + Ansi.CYAN + "┃"
//                        + Ansi.DEFAULT + "(" + lineAndCol[0] + "," + lineAndCol[1] + ")" + Ansi.CYAN + "┃"
//                        + Ansi.DEFAULT + TokenType.EOF.name + Ansi.CYAN + "┃"
//                        + Ansi.DEFAULT)
//            )
//        }

        return Token(span, TokenType.EOF, null)
    }

    /**
     * 错误恢复，扫描直到期望的字符
     */
    private fun recover(vararg expectedChars: Char) {
        while (!this.isAtEnd) {
            for (expected in expectedChars) {
                if (check(expected)) {
                    return
                }
            }
            advance()
        }
    }

    /**
     * 错误恢复，扫描直到期望的字符
     * @param predicate 满足该条件则退出
     */
    private fun recover(predicate: Boolf<Char>) {
        while (!this.isAtEnd) {
            if (predicate.get(peek())) {
                return
            }
            advance()
        }
    }

    private fun error(text: String): LexerDiag {
        val e = LexerDiag(text, Diagnostic.DiagLevel.ERROR)
        problems.addError(e)
        return e
    }

    private fun warning(text: String): LexerDiag {
        val w = LexerDiag(text, Diagnostic.DiagLevel.WARNING)
        problems.addWarning(w)
        return w
    }

    /** 用当前文件索引构造 span，供 label 使用 */
    private fun span(from: Int, to: Int): Span = Span.between(sourceFile.index, from, to)

    fun createSnapshot(): LexerSnapshot {
        return LexerSnapshot(start, current, isPrevNewline)
    }

    fun restoreSnapshot(snapshot: LexerSnapshot) {
        start = snapshot.start
        current = snapshot.current
        isPrevNewline = snapshot.isPrevNewline
    }

    data class LexerSnapshot(val start: Int, val current: Int, val isPrevNewline: Boolean)

    companion object {
        val confusedChars = mapOf(
            '！' to '!',
            '。' to '.',
            '：' to ':',
            '；' to '；',
            '，' to ',',
            '（' to '(',
            '）' to ')',
            '【' to '[',
            '】' to ']',
            '？' to '?',
            '“' to '"',
            '”' to '"',
        )
    }
}