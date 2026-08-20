package mlogix.compiler

import arc.files.Fi
import arc.graphics.Color
import arc.graphics.Colors
import arc.struct.Seq
import arc.util.I18NBundle.createBundle
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.core.token.TokenType.*
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.passes.parsing.Lexer
import mlogix.util.I18N
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LexerTest {
    val diagHandler = DiagHandler()
    val lexer = Lexer(diagHandler)

    companion object {
        @BeforeAll
        @JvmStatic
        fun init() {
            val projectDirectory = Fi.get(System.getProperty("user.dir"))
            I18N.bundle = createBundle(projectDirectory.child("assets/bundles/bundle"))
        }
    }

    @Test
    fun `tokenize simple arithmetic expression`() {
        val source = "3 + 5 * 2"
        val tokens = lexer.tokenize(source)

        val expected = Seq.with(
            token(INT, 3.0),
            token(PLUS),
            token(INT, 5.0),
            token(STAR),
            token(INT, 2.0),
        )
        assertEquals(expected, tokens, "Token 列表必须完全匹配")
    }

    @Test
    fun `tokenize operators and separators`() {
        val source =
            "+ - * / ** % %% // & | ^ << >> >>> ~ ++ -- = == != === !== < > <= >= && || ! : := :< -> ; , . ( ) [ ] { }"
        val tokens = lexer.tokenize(source)

        val expected = Seq.with(
            token(PLUS),
            token(MINUS),
            token(STAR),
            token(SLASH),
            token(STAR_STAR),
            token(PERCENT),
            token(PERCENT_PERCENT),
            token(SLASH_SLASH),
            token(AND),
            token(OR),
            token(CARET),
            token(SHL),
            token(SAR),
            token(SHR),
            token(TILDE),
            token(PLUS_PLUS),
            token(MINUS_MINUS),
            token(ASSIGN),
            token(EQ_EQ),
            token(BANG_EQ),
            token(EQ_EQ_EQ),
            token(BANG_EQ_EQ),
            token(LESS),
            token(GREATER),
            token(LESS_EQ),
            token(GREATER_EQ),
            token(AND_AND),
            token(OR_OR),
            token(BANG),
            token(COLON),
            token(COLON_ASSIGN),
            token(COLON_LESS),
            token(ARROW),
            token(SEMICOLON),
            token(COMMA),
            token(DOT),
            token(LPAREN),
            token(RPAREN),
            token(LBRACKET),
            token(RBRACKET),
            token(LBRACE),
            token(RBRACE)
        )
        // Suggestion: some tokenizers may produce NEWLINE/EOF tokens; we keep equality strict as in the original test
        assertEquals(expected, tokens, "Operators & separators 必须被正确分词")
    }

    @Test
    fun `tokenize ints`() {
        // 整数底层使用Double，契合Logic的底层
        assertEquals(intSeq(123.0), lexer.tokenize("123"))
        assertEquals(intSeq(123.0), lexer.tokenize("1_23"))
    }

    @Test
    fun `tokenize nums`() {
        assertEquals(numSeq(123.0), lexer.tokenize("123."))
        assertEquals(numSeq(123.123), lexer.tokenize("123.123"))
        assertEquals(numSeq(123e12), lexer.tokenize("123e12"))
        assertEquals(numSeq(123.123e12), lexer.tokenize("123.123e12"))
        assertEquals(numSeq(123.123e-12), lexer.tokenize("123.123e-12"))

        // 加入分隔符
        assertEquals(numSeq(123.0), lexer.tokenize("1_23."))
        assertEquals(numSeq(123.123), lexer.tokenize("12_3.12_3"))
        assertEquals(numSeq(123e12), lexer.tokenize("12_3e1_2"))
        assertEquals(numSeq(123.123e12), lexer.tokenize("1_23.1_23e1_2"))
        assertEquals(numSeq(123.123e-12), lexer.tokenize("1_23.1_23e-1_2"))
    }

    @Test
    fun `tokenize colors`() {
        assertEquals(
            Seq.with(Colors.get("red").let { colSeq(Color.toDoubleBits(it.r, it.g, it.b, it.a)) }),
            lexer.tokenize("0%red")
        )
        assertEquals(
            Seq.with(Colors.get("RED").let { colSeq(Color.toDoubleBits(it.r, it.g, it.b, it.a)) }),
            lexer.tokenize("0%RED")
        )
        assertEquals(
            Seq.with(Colors.get("DARK_GRAY").let { colSeq(Color.toDoubleBits(it.r, it.g, it.b, it.a)) }),
            lexer.tokenize("0%DARK_GRAY")
        )
        assertEquals(
            Seq.with(Colors.get("darkgray").let { colSeq(Color.toDoubleBits(it.r, it.g, it.b, it.a)) }),
            lexer.tokenize("0%darkgray")
        )

        assertEquals(
            colSeq(Color.toDoubleBits(0xff, 0x7f, 0x10, 0xff)),
            lexer.tokenize("0%ff7f10")
        )
        assertEquals(
            colSeq(Color.toDoubleBits(0xff, 0x7f, 0x10, 0x30)),
            lexer.tokenize("0%ff7f1030")
        )
        assertEquals(
            colSeq(Color.toDoubleBits(0xff, 0x7f, 0x10, 0xff)),
            lexer.tokenize("0%FF7F10")
        )
        assertEquals(
            colSeq(Color.toDoubleBits(0xff, 0x7f, 0x10, 0x30)),
            lexer.tokenize("0%FF7F1030")
        )
    }

    @Test
    fun `tokenize strings`() {
        assertEquals(strSeq("hello world"), lexer.tokenize("\"hello world\""))
        assertEquals(strSeq("hello \\nworld"), lexer.tokenize("\"hello \\nworld\""))

        // 使用全角符号不合法但是结果也得对
        assertEquals(strSeq("hello world"), lexer.tokenize("“hello world”"))
        assertEquals(strSeq("hello \\nworld"), lexer.tokenize("“hello \\nworld”"))
    }

    private fun token(type: TokenType, literal: Any? = null): Token {
        return Token(Span(0, 0, 0), type, literal)
    }

    private fun intSeq(literal: Double): Seq<Token> {
        return Seq.with(token(INT, literal))
    }

    private fun numSeq(literal: Double): Seq<Token> {
        return Seq.with(token(NUM, literal))
    }

    private fun colSeq(literal: Double): Seq<Token> {
        return Seq.with(token(COL, literal))
    }

    private fun strSeq(literal: String): Seq<Token> {
        return Seq.with(token(STR, literal))
    }
}
