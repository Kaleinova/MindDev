package mlogix.compiler.semantics

import mlogix.compiler.support.CompilerTestBase
import mlogix.compiler.support.assertLabelSourceTexts
import mlogix.compiler.support.assertSingleError
import org.junit.jupiter.api.Test

/**
 * 诊断契约示例：不只断言「有 1 个错误」，而是钉死 **哪条诊断 + 落在哪个源码片段**。
 *
 * 这是 A 阶段给后续大量测试定的书写范式；旧用例可以按需逐步迁移到这种断言。
 */
class DiagnosticContractTest : CompilerTestBase() {

    @Test
    fun `unknown enum variant reports its key and label`() {
        val result = analyzeFile(
            """
            enum Color {
                Red
            }
            set c = Color.Purple
            """.trimIndent()
        )

        val error = result.assertSingleError("diag.no-such-variant", "Color", "Purple")
        error.assertLabelSourceTexts(result.source, "Purple")
    }

    @Test
    fun `enum type in value position reports its key and label`() {
        val result = analyzeFile(
            """
            enum Color {
                Red
            }
            set c = Color
            """.trimIndent()
        )

        val error = result.assertSingleError("diag.enum-as-value", "Color")
        error.assertLabelSourceTexts(result.source, "Color")
    }

    @Test
    fun `duplicate variant reports its key and label`() {
        val result = analyzeFile(
            """
            enum Color {
                Red
                Red
            }
            """.trimIndent()
        )

        val error = result.assertSingleError("diag.duplicate-variant", "Color", "Red")
        error.assertLabelSourceTexts(result.source, "Red")
    }
}
