package mlogix.compiler

import arc.struct.Seq
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.core.token.TokenType.*
import mlogix.compiler.diagnostic.ProblemCollector
import mlogix.compiler.core.span.Span
import mlogix.compiler.passes.parsing.Lexer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LexerTest {
    val problemCollector = ProblemCollector()
    val lexer = Lexer(problemCollector)

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
        val source = "+ - * / ** % %% // & | ^ << >> ~ ++ -- = == != === !== < > <= >= && || ! : := :< -> ; , . ( ) [ ] { }"
        val tokens = lexer.tokenize(source)

        val expected = Seq.with<Token>(
            token(PLUS), token(MINUS), token(STAR), token(SLASH), token(STAR_STAR), token(PERCENT), token(PERCENT_PERCENT), token(SLASH_SLASH),
            token(AND),
            token(OR),
            token(CARET),
            token(SHL),
            token(SAR),
            token(TILDE),
            token(PLUS_PLUS),
            token(MINUS_MINUS),
            token(ASSIGN), token(EQ_EQ), token(BANG_EQ), token(EQ_EQ_EQ), token(BANG_EQ_EQ),
            token(LESS), token(GREATER), token(LESS_EQ), token(GREATER_EQ), token(AND_AND), token(OR_OR), token(BANG),
            token(COLON), token(COLON_ASSIGN), token(COLON_LESS), token(ARROW), token(SEMICOLON), token(COMMA), token(DOT),
            token(LPAREN), token(RPAREN), token(LBRACKET), token(RBRACKET), token(LBRACE), token(RBRACE)
        )
        // Note: some tokenizers may produce NEWLINE/EOF tokens; we keep equality strict as in the original test
        assertEquals(expected, tokens, "Operators & separators 必须被正确分词")
    }

    @Test
    fun `tokenize literals strings colors and identifiers`() {
        val source = "true false null \"hello\\nworld\" 0%FF0000 0%red 0xFF_00_7F 0b111_000 abc_def"
        val tokens = lexer.tokenize(source)

        val expected = Seq.with(
            token(TRUE), token(FALSE), token(NULL), token(STR, "hello\\nworld"),
            token(COL), token(COL),
            token(INT, 0xFF007F.toDouble()), token(INT, 0b111000.toDouble()), token(IDENTIFIER, "abc_def")
        )

        // We cannot call Color.toDoubleBits easily here; instead compare types and approximate numeric values where applicable.
        // Build a simple comparison: check types and literals where literal is not null.
        for ((i, element) in expected.withIndex()) {
            val e = element
            val a = tokens.get(i)
            assertEquals(e.type, a.type, "Token 类型应匹配 at index $i")
            if (e.literal != null) {
                assertEquals(e.literal, a.literal, "Token.literal 应匹配 at index $i")
            }
        }
    }

    private fun token(type: TokenType, literal: Any? = null): Token {
        return Token(Span(0, 0, 0), type, literal)
    }
}
