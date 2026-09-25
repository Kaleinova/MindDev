package mlogix.compiler

import arc.files.Fi
import arc.util.I18NBundle.createBundle
import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.SourceMap
import mlogix.compiler.core.span.Span
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.passes.parsing.Lexer
import mlogix.compiler.passes.parsing.Parser
import mlogix.compiler.passes.resolution.Resolver
import mlogix.compiler.passes.typing.TypeInferencer
import mlogix.compiler.pipeline.CompilationContext
import mlogix.util.I18N
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * 诊断渲染（[Diagnostic.render]）：重点是同一行上**重叠/嵌套** label 的分行渲染。
 *
 * 回归背景：`renderLine` 曾用 `space < 0 → continue` 直接丢弃与前一 label 重叠的 label，
 * 于是「主 label 圈整个类型表达式 + 次级 label 圈其中一个类型实参」这类标注永远画不出来。
 */
class DiagnosticRenderTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun init() {
            val projectDirectory = Fi.get(System.getProperty("user.dir"))
            I18N.bundle = createBundle(projectDirectory.child("assets/bundles/bundle"))
        }
    }

    /** 用一段源码 + 若干 (start, end, text) 标注渲染出诊断文本 */
    private fun render(source: String, vararg labels: Triple<Int, Int, String>): String {
        val sourceMap = SourceMap(null)
        val sourceFile = sourceMap.loadSourceMap(source)
        val diagnostic = Diagnostic.SemanticDiag("测试诊断", Diagnostic.DiagLevel.ERROR)
        for ((start, end, text) in labels) {
            diagnostic.label(Span.between(sourceFile.index, start, end), text)
        }
        return diagnostic.render(sourceMap)
    }

    /** 找出含 [needle] 的行号（找首行），找不到返回 -1 */
    private fun lineOf(rendered: String, needle: String): Int {
        return rendered.lines().indexOfFirst { it.contains(needle) }
    }

    // ========== 不重叠：保持原有单标注行样式 ==========

    @Test
    fun `non overlapping labels share one marker row`() {
        val source = "set a : Int = \"s\""
        val rendered = render(
            source,
            Triple(8, 11, "期望类型"),
            Triple(14, 17, "实际类型"),
        )
        val markerRow = lineOf(rendered, "^")
        assertTrue(markerRow >= 0, "应画出主标记行")
        // 两个 label 在同一标注行：`---` 与 `^^^` 出现在同一行
        assertTrue(rendered.lines()[markerRow].contains("---"), "同一标注行应有次级标记")
        assertTrue(rendered.lines()[markerRow].contains("^^^"), "同一标注行应有主标记")
        assertTrue(rendered.contains("期望类型"))
        assertTrue(rendered.contains("实际类型"))
    }

    // ========== 重叠/嵌套：不再丢弃 ==========

    @Test
    fun `overlapping label is rendered on the next annotation row instead of being dropped`() {
        val source = "set a = Option<Int, Str>.Some(1)"
        val rendered = render(
            source,
            Triple(8, 24, "主标注"),   // Option<Int, Str>
            Triple(20, 23, "次标注"),  // Str（完全落在主标注区间内）
        )
        assertTrue(rendered.contains("主标注"), "主标注必须渲染")
        assertTrue(rendered.contains("次标注"), "与主标注重叠的次标注不得被丢弃")

        val primaryRow = lineOf(rendered, "主标注")
        val secondaryRow = lineOf(rendered, "次标注")
        assertTrue(secondaryRow > primaryRow, "重叠标注应渲染在后续标注行")
    }

    @Test
    fun `deeply nested labels stack into multiple rows`() {
        val source = "set a = Option<Option<Int>>.Some(1)"
        val rendered = render(
            source,
            Triple(8, 30, "外层"),
            Triple(15, 28, "中层"),
            Triple(22, 25, "内层"),
        )
        for (text in listOf("外层", "中层", "内层")) {
            assertTrue(rendered.contains(text), "$text 必须渲染")
        }
        assertTrue(lineOf(rendered, "外层") < lineOf(rendered, "中层"))
        assertTrue(lineOf(rendered, "中层") < lineOf(rendered, "内层"))
    }

    @Test
    fun `labels sharing the same start column still both render`() {
        val source = "set a = x"
        val rendered = render(
            source,
            Triple(8, 9, "短标注"),
            Triple(8, 9, "同起点标注"),
        )
        assertTrue(rendered.contains("短标注"))
        assertTrue(rendered.contains("同起点标注"))
    }

    // ========== 端到端：多余类型实参的次级 label ==========

    @Test
    fun `extra type argument label is visible in the rendered diagnostic`() {
        val rendered = renderEnumArity(
            """
            enum Option<T> {
                None
                Some(T)
            }
            set a = Option<Int, Str>.Some(1)
            """.trimIndent()
        )
        val extraText = I18N.bundle.get("diag.explicit-type-arg-count.extra")
        assertTrue(rendered.contains(extraText), "「$extraText」应出现在错误代码片段里")
        // 主 label（整个类型表达式）与次级 label 都要在
        assertTrue(rendered.contains("Option<Int, Str>") || rendered.contains("请传入恰好"))
    }

    @Test
    fun `multiple extra type arguments all render on their own rows`() {
        val rendered = renderEnumArity(
            """
            enum Option<T> {
                None
                Some(T)
            }
            set a = Option<Int, Str, Bool>.Some(1)
            """.trimIndent()
        )
        val extraText = I18N.bundle.get("diag.explicit-type-arg-count.extra")
        val occurrences = rendered.split(extraText).size - 1
        assertTrue(occurrences >= 2, "两个多余实参应各有一处标注，实际 $occurrences 处")
    }

    /** 跑一遍「解析 → 名称解析 → 类型推断」并把首条错误渲染出来 */
    private fun renderEnumArity(source: String): String {
        val sourceMap = SourceMap(null)
        val context = CompilationContext(DiagHandler(sourceMap), CompilerConfig())
        val sourceFile = sourceMap.loadSourceMap(source)
        val ast = Parser(Lexer(context), context).parse(sourceFile)
        val resolved = Resolver(context).resolve(ast, sourceFile)
        TypeInferencer(context).analyze(resolved, sourceFile)

        assertEquals(1, context.diagHandler.errorNum())
        return context.diagHandler.errors[0].render(sourceMap)
    }
}
