package mlogix.compiler.parser

import arc.struct.Seq
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.support.CompilerTestBase
import mlogix.compiler.support.assertLabelSourceTexts
import mlogix.util.I18N
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 定界符作用域栈（[mlogix.compiler.passes.parsing.Parser]）的报错与错误恢复。
 *
 * 覆盖两类行为：
 * - 报错详细程度：`(` / `[` / `{` 的 opener 位置、所属构造名、期望的闭合符与插入建议；
 * - 错误恢复：错配的闭合符只终结最内层作用域，后续语句仍留在原作用域里继续解析。
 */
class ParserRecoveryTest : CompilerTestBase() {

    // ========== 报错详细程度 ==========

    @Test
    fun `mismatched closer in call arguments names opener and scope`() {
        val source = """
            fn outer() {
                foo(1 }
            }
        """.trimIndent()

        val errors = parseErrors(source)
        // 第一处：调用实参缺 `)`；第一处 `}` 被当成块闭合后，末尾的 `}` 变成多余
        assertEquals(
            2, errors.size,
            "错误数不符，实际:\n${errorMessages(errors)}"
        )
        assertEquals(format("diag.mismatched-delimiter", TokenType.RPAREN, TokenType.RBRACE), errors[0].message)
        errors[0].assertLabelSourceTexts(source, "}", "(")
        assertEquals(format("diag.unmatched-closer", TokenType.RBRACE), errors[1].message)
    }

    @Test
    fun `unclosed delimiter at end of file points at opener`() {
        val source = "fn outer() {\n    foo(1\n"

        val errors = parseErrors(source)
        assertEquals(2, errors.size, "错误数不符，实际:\n${errorMessages(errors)}")
        assertEquals(format("diag.unclosed-delimiter", TokenType.RPAREN), errors[0].message)
        errors[0].assertLabelSourceTexts(source, "(")
        assertEquals(format("diag.unclosed-delimiter", TokenType.RBRACE), errors[1].message)
        errors[1].assertLabelSourceTexts(source, "{")
    }

    @Test
    fun `missing parameter closer at end of file`() {
        val source = "fn outer(a\n"
        val error = assertSingleParseError(source, "diag.unclosed-delimiter", TokenType.RPAREN)
        error.assertLabelSourceTexts(source, "(")
    }

    @Test
    fun `missing struct body closer at end of file`() {
        val source = "struct Point {\n    x: Num\n"
        val error = assertSingleParseError(source, "diag.unclosed-delimiter", TokenType.RBRACE)
        error.assertLabelSourceTexts(source, "{")
    }

    @Test
    fun `missing enum body closer at end of file`() {
        val source = "enum Color {\n    Red\n"
        val error = assertSingleParseError(source, "diag.unclosed-delimiter", TokenType.RBRACE)
        error.assertLabelSourceTexts(source, "{")
    }

    @Test
    fun `missing match body closer at end of file`() {
        val source = "match c {\n    Color.Red -> { }\n"
        val error = assertSingleParseError(source, "diag.unclosed-delimiter", TokenType.RBRACE)
        error.assertLabelSourceTexts(source, "{")
    }

    @Test
    fun `mismatched closer in pattern payload`() {
        val source = "match c {\n    Option.Some(x }"
        val error = assertSingleParseError(source, "diag.mismatched-delimiter", TokenType.RPAREN, TokenType.RBRACE)
        error.assertLabelSourceTexts(source, "}", "(")
    }

    @Test
    fun `missing use list closer at end of file`() {
        val source = "use a.{b, c\n"
        val error = assertSingleParseError(source, "diag.unclosed-delimiter", TokenType.RBRACE)
        error.assertLabelSourceTexts(source, "{")
    }

    @Test
    fun `missing tuple closer at end of file`() {
        val source = "set a = (1, 2\n"
        val error = assertSingleParseError(source, "diag.unclosed-delimiter", TokenType.RPAREN)
        error.assertLabelSourceTexts(source, "(")
    }

    @Test
    fun `missing array closer at end of file`() {
        val source = "set a = {1, 2\n"
        val error = assertSingleParseError(source, "diag.unclosed-delimiter", TokenType.RBRACE)
        error.assertLabelSourceTexts(source, "{")
    }

    @Test
    fun `missing index closer at end of file`() {
        val source = "set a = b[1\n"
        val error = assertSingleParseError(source, "diag.unclosed-delimiter", TokenType.RBRACKET)
        error.assertLabelSourceTexts(source, "[")
    }

    // ========== 错误恢复 ==========

    @Test
    fun `closer without owner in call arguments is suggested as a replacement`() {
        val source = "print(\"a\"}"

        val error = assertSingleParseError(source, "diag.mismatched-delimiter", TokenType.RPAREN, TokenType.RBRACE)
        error.assertLabelSourceTexts(source, "}", "(")

        val help = error.suggestions.firstOrNull { it is Diagnostic.Help } as? Diagnostic.Help
        assertNotNull(help, "应给出替换建议，实际建议：${error.suggestions}")
        val replacement = help!!.labels.firstOrNull { it.style == Diagnostic.LabelStyle.Replace }
        assertNotNull(replacement, "替换建议应使用 Replace 标注")
        assertEquals(")", replacement!!.text, "替换内容应为 `)`")
    }

    @Test
    fun `unmatched closer after a complete statement is reported`() {
        val source = "set a = 1 )\n"
        val error = assertSingleParseError(source, "diag.unmatched-closer", TokenType.RPAREN)
        error.assertLabelSourceTexts(source, ")")
    }

    @Test
    fun `unmatched closer inside block is dropped and following statement stays in block`() {
        val source = """
            fn outer() {
                foo(1) ]
                set a = 1
            }
        """.trimIndent()

        val error = assertSingleParseError(source, "diag.unmatched-closer", TokenType.RBRACKET)
        error.assertLabelSourceTexts(source, "]")

        val program = parse(source) as Stmt.Program
        val function = program.stmts[0] as Stmt.Fn
        val body = function.body as Stmt.Block
        assertEquals(2, body.stmts.size, "块内应仍有两段语句，实际：${body.stmts}")
    }

    @Test
    fun `missing call closer before a statement ends the call but keeps the statement`() {
        val source = """
            fn outer() {
                foo(1
                set a = 1
            }
        """.trimIndent()

        val error = assertSingleParseError(
            source,
            "diag.miss-closer",
            TokenType.RPAREN,
            format("diag.scope.call-args"),
        )
        error.assertLabelSourceTexts(source, "set", "(")

        val program = parse(source) as Stmt.Program
        val function = program.stmts[0] as Stmt.Fn
        val body = function.body as Stmt.Block
        assertEquals(1, body.stmts.size, "残缺的调用应被丢弃，只保留 set 语句，实际：${body.stmts}")
        assertTrue(body.stmts[0] is Stmt.SetVar, "后一条语句应仍是 set 语句")
    }

    @Test
    fun `missing for brace does not swallow the next statement`() {
        val source = """
            fn outer() {
                for i in 1:<3
                set a = 1
            }
        """.trimIndent()

        val error = assertSingleParseError(source, "diag.miss-token", TokenType.LBRACE)
        // 第 1 个 label 标在「当前位置」，第 2 个 label 标在 `for` 语句开头
        assertEquals(
            "for",
            source.substring(error.labels[1].span.start, error.labels[1].span.end),
            "应标出 `for` 语句开头",
        )

        val program = parse(source) as Stmt.Program
        val function = program.stmts[0] as Stmt.Fn
        val body = function.body as Stmt.Block
        assertEquals(2, body.stmts.size, "for 与 set 都应保留，实际：${body.stmts}")
    }

    // ========== 回归：合法代码不产生诊断 ==========

    @Test
    fun `well formed delimiters produce no parse errors`() {
        val source = """
            use a.{b, c}
            struct Point { x: Num, y: Num = 0 }
            enum Option<T> { None, Some(T) }
            fn outer(p: Point) -> r : Num {
                set arr = {1, 2, 3}
                set empty = {}
                set tuple = (1, 2)
                set value = arr[0]
                set multi = add(
                    1,
                    2
                )
                if value > 0 { print(value) } else { print(0) }
                for i in 1:<3 { print(i) }
                while true { break }
                match tuple {
                    Option.Some(v) -> { print(v) }
                    _ -> { }
                }
                return value
            }
        """.trimIndent()
        assertParseErrorCount(source, 0)
    }

    // ========== 工具 ==========

    /** 只跑解析阶段，返回解析期诊断（含词法诊断）。 */
    private fun parseErrors(source: String): Seq<Diagnostic> {
        parse(source)
        return diagHandler.errors
    }

    private fun assertParseErrorCount(source: String, expected: Int) {
        val errors = parseErrors(source)
        assertEquals(expected, errors.size, "错误数不符，实际:\n${errorMessages(errors)}")
    }

    private fun assertSingleParseError(source: String, key: String, vararg args: Any): Diagnostic {
        val errors = parseErrors(source)
        assertEquals(1, errors.size, "应只有一条解析错误，实际:\n${errorMessages(errors)}")
        assertEquals(format(key, *args), errors[0].message, "唯一错误的文案不符")
        return errors[0]
    }

    private fun errorMessages(errors: Seq<Diagnostic>): String =
        errors.joinToString("\n") { it.message }

    private fun format(key: String, vararg args: Any): String =
        if (args.isEmpty()) I18N.bundle.get(key) else I18N.bundle.format(key, *args)
}
