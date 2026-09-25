package mlogix.compiler

import arc.files.Fi
import arc.util.I18NBundle.createBundle
import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.diagnostic.DiagHandler
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
 * match 模式匹配的语义：模式类型检查（绑定/载荷/枚举一致）、
 * 穷尽性（枚举，缺失=错误）、冗余分支（告警）、绑定名撞变体名（告警）。
 *
 * 设计约定（见 `docs/grammar/fast-learning.md` §12）：
 * - 模式只有 `_`、裸标识符绑定、`枚举名.变体(...)` 三种（无字面量模式）；
 * - 裸标识符一律是**绑定**（Rust 风格），要匹配变体必须写全 `枚举名.变体`。
 */
class MatchTest {
    private val context = CompilationContext(DiagHandler(), CompilerConfig())
    private val lexer = Lexer(context)
    private val parser = Parser(lexer, context)
    private val resolver = Resolver(context)
    private val inferencer = TypeInferencer(context)

    companion object {
        @BeforeAll
        @JvmStatic
        fun init() {
            val projectDirectory = Fi.get(System.getProperty("user.dir"))
            I18N.bundle = createBundle(projectDirectory.child("assets/bundles/bundle"))
        }
    }

    private fun analyze(source: String) {
        context.diagHandler.clear()
        val sourceFile = SourceFile(source)
        val ast = parser.parse(sourceFile)
        val result = resolver.resolve(ast, sourceFile)
        inferencer.analyze(result, sourceFile)
    }

    private fun errors(source: String): Int {
        analyze(source)
        return context.diagHandler.errorNum()
    }

    private fun warnings(source: String): Int {
        analyze(source)
        return context.diagHandler.warningNum()
    }

    /** 断言诊断文本里含某个 key 格式化后的片段（中英皆可，避免硬编码文案） */
    private fun assertErrorMentions(source: String, vararg keyAndArgs: Any) {
        analyze(source)
        val key = keyAndArgs[0] as String
        val args = keyAndArgs.drop(1).toTypedArray()
        val expected = if (args.isEmpty()) I18N.bundle.get(key) else I18N.bundle.format(key, *args)
        val messages = context.diagHandler.errors.joinToString("\n") { it.message }
        assertTrue(messages.contains(expected), "错误消息应包含「$expected」，实际:\n$messages")
    }

    private fun assertWarningMentions(source: String, vararg keyAndArgs: Any) {
        analyze(source)
        val key = keyAndArgs[0] as String
        val args = keyAndArgs.drop(1).toTypedArray()
        val expected = if (args.isEmpty()) I18N.bundle.get(key) else I18N.bundle.format(key, *args)
        val messages = context.diagHandler.warnings.joinToString("\n") { it.message }
        assertTrue(messages.contains(expected), "告警应包含「$expected」，实际:\n$messages")
    }

    private val optionEnum = """
        enum Option<T> {
            None
            Some(T)
        }
    """.trimIndent()

    // ========== 正例 ==========

    @Test
    fun `wildcard and variant arms cover an enum`() {
        assertEquals(
            0,
            errors(
                """
                $optionEnum
                set o = Option.None
                match o {
                    Option.None -> { }
                    Option.Some(x) -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `wildcard makes the match exhaustive`() {
        assertEquals(
            0,
            errors(
                """
                $optionEnum
                set o = Option.Some(1)
                match o {
                    Option.Some(x) -> { }
                    _ -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `payload binding gets the payload type`() {
        assertEquals(
            0,
            errors(
                """
                $optionEnum
                set o = Option.Some(1)
                match o {
                    Option.Some(x) -> { set n : Int = x }
                    Option.None -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `binding pattern is irrefutable and binds the whole value`() {
        assertEquals(
            0,
            errors(
                """
                $optionEnum
                set o = Option.Some(1)
                match o {
                    z -> { set same : Option<Int> = z }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `nested variant patterns are matched recursively`() {
        assertEquals(
            0,
            errors(
                """
                $optionEnum
                set oo = Option.Some(Option.None)
                match oo {
                    Option.None -> { }
                    Option.Some(Option.None) -> { }
                    Option.Some(Option.Some(y)) -> { set n : Int = y }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `non enum scrutinee only needs a fallback arm`() {
        assertEquals(
            0,
            errors(
                """
                set n = 1
                match n {
                    _ -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `struct variant payload binds positionally`() {
        assertEquals(
            0,
            errors(
                """
                enum Shape {
                    Point
                    Rect { width: Int, height: Int }
                }
                set s = Shape.Rect(1, 2)
                match s {
                    Shape.Point -> { }
                    Shape.Rect(w, h) -> { set a : Int = w; set b : Int = h }
                }
                """.trimIndent()
            )
        )
    }

    // ========== 绑定与作用域 ==========

    @Test
    fun `binding is not visible outside its arm`() {
        // 分支体外的 `x` 未声明 → 1 个错误；同时穷尽性满足
        assertEquals(
            1,
            errors(
                """
                $optionEnum
                set o = Option.Some(1)
                match o {
                    Option.Some(x) -> { }
                    Option.None -> { }
                }
                set n = x
                """.trimIndent()
            )
        )
    }

    @Test
    fun `binding does not leak into the next arm`() {
        assertEquals(
            1,
            errors(
                """
                $optionEnum
                set o = Option.Some(1)
                match o {
                    Option.Some(x) -> { }
                    Option.None -> { set n = x }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `binding may shadow an outer variable`() {
        assertEquals(
            0,
            errors(
                """
                $optionEnum
                set x = 1
                set o = Option.Some("s")
                match o {
                    Option.Some(x) -> { set s : Str = x }
                    Option.None -> { }
                }
                """.trimIndent()
            )
        )
    }

    // ========== 模式类型检查 ==========

    @Test
    fun `variant pattern of a different enum is rejected`() {
        // 用户可见的关键行为：错误模式（`Other.Blue` 对 `Color`）只报一条类型不匹配，
        // 不得再因为它叠一条「分支不可达」告警（`Color.Red` 依然是可达的）。
        analyze(
            """
            enum Color {
                Red
            }
            enum Other {
                Blue
            }
            set c = Color.Red
            match c {
                Other.Blue -> { }
                Color.Red -> { }
            }
            """.trimIndent()
        )

        assertEquals(1, context.diagHandler.errorNum(), "应只有类型不匹配一个错误")
        assertEquals(0, context.diagHandler.warningNum(), "不应再有「分支不可达」告警")

        val errors = context.diagHandler.errors.joinToString("\n") { it.message }
        val mismatch = I18N.bundle.format("diag.type-mismatch", "Other", "Color")
        assertTrue(errors.contains(mismatch), "应报类型不匹配，实际:\n$errors")
    }

    @Test
    fun `a variant of a different enum does not cover a same named variant`() {
        // `Other.Blue` 与 `Color.Blue` 只是同名：前者匹配不到 `Color` 的取值，类型检查已就地
        // 报「类型不匹配」。覆盖性分析的前提（每个模式都是本枚举的取值之一）随之被破坏，
        // 因此不得再叠可达性/穷尽性结论——否则同名变体会把 `Color.Blue` 算作已覆盖。
        analyze(
            """
            enum Color {
                Red
                Blue
            }
            enum Other {
                Blue
            }
            set c = Color.Red
            match c {
                Color.Red -> { }
                Other.Blue -> { }
            }
            """.trimIndent()
        )

        assertEquals(1, context.diagHandler.errorNum(), "应只有类型不匹配一个错误")
        assertEquals(0, context.diagHandler.warningNum(), "错误模式不得再触发不可达告警")

        val warnings = context.diagHandler.warnings.joinToString("\n") { it.message }
        assertTrue(
            !warnings.contains(I18N.bundle.get("diag.unreachable-arm")),
            "类型不匹配的模式不应再报「不可达」，实际:\n$warnings",
        )
    }

    @Test
    fun `variant pattern on a non enum value is rejected`() {
        assertEquals(
            1,
            errors(
                """
                $optionEnum
                set n = 1
                match n {
                    Option.None -> { }
                    _ -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a mismatched variant nested in a payload suppresses coverage conclusions`() {
        // 错误在载荷里层（`Some(Other.Blue)`）：外层看着没错，判定必须递归到载荷
        analyze(
            """
            enum Color {
                Red
                Blue
            }
            enum Other {
                Blue
            }
            $optionEnum
            set oc = Option.Some(Color.Red)
            match oc {
                Option.None -> { }
                Option.Some(Other.Blue) -> { }
                Option.Some(Color.Red) -> { }
            }
            """.trimIndent()
        )

        assertEquals(1, context.diagHandler.errorNum(), "应只有载荷里的类型不匹配一个错误")
        val warnings = context.diagHandler.warnings.joinToString("\n") { it.message }
        assertTrue(
            !warnings.contains(I18N.bundle.get("diag.unreachable-arm")),
            "错误模式不应让后面的正常分支变成「不可达」，实际:\n$warnings",
        )
    }

    @Test
    fun `unknown variant in a pattern is rejected`() {
        assertEquals(
            1,
            errors(
                """
                $optionEnum
                set o = Option.None
                match o {
                    Option.Purple -> { }
                    _ -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `payload pattern count must match the variant`() {
        assertEquals(
            1,
            errors(
                """
                $optionEnum
                set o = Option.Some(1)
                match o {
                    Option.Some -> { }
                    Option.None -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `pattern on a non enum name is rejected`() {
        assertEquals(
            1,
            errors(
                """
                set c = 1
                match c {
                    c.Red -> { }
                    _ -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `literal pattern is reported as unsupported`() {
        // 消息里带的是 Token 类型名（与 `diag.miss-expression` 等既有报错一致）
        assertErrorMentions(
            """
            set n = 1
            match n {
                1 -> { }
                _ -> { }
            }
            """.trimIndent(),
            "diag.miss-pattern",
            TokenType.INT,
        )
    }

    // ========== 穷尽性（缺失 = 错误，含反例） ==========

    @Test
    fun `missing variant is an error`() {
        assertEquals(
            1,
            errors(
                """
                $optionEnum
                set o = Option.None
                match o {
                    Option.None -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `missing variant error names the uncovered variant`() {
        assertErrorMentions(
            """
            $optionEnum
            set o = Option.None
            match o {
                Option.None -> { }
            }
            """.trimIndent(),
            "diag.non-exhaustive-match",
            "Option.Some(_)",
        )
    }

    @Test
    fun `empty match on an enum is an error`() {
        assertEquals(
            1,
            errors(
                """
                $optionEnum
                set o = Option.None
                match o { }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `missing nested case is reported with a nested counterexample`() {
        // `Some(None)` 不覆盖 `Some(Some(_))`：模式矩阵要递归到载荷才能发现
        assertErrorMentions(
            """
            $optionEnum
            set oo = Option.Some(Option.None)
            match oo {
                Option.None -> { }
                Option.Some(Option.None) -> { }
            }
            """.trimIndent(),
            "diag.non-exhaustive-match",
            "Option.Some(Option.Some(_))",
        )
    }

    @Test
    fun `nested arms can be exhaustive without a wildcard`() {
        assertEquals(
            0,
            errors(
                """
                $optionEnum
                set oo = Option.Some(Option.None)
                match oo {
                    Option.None -> { }
                    Option.Some(Option.None) -> { }
                    Option.Some(Option.Some(x)) -> { }
                }
                """.trimIndent()
            )
        )
    }

    // ========== 冗余分支（告警） ==========

    @Test
    fun `duplicate variant arm is unreachable`() {
        assertWarningMentions(
            """
            $optionEnum
            set o = Option.None
            match o {
                Option.None -> { }
                Option.None -> { }
                Option.Some(x) -> { }
            }
            """.trimIndent(),
            "diag.unreachable-arm",
        )
    }

    @Test
    fun `arm after a wildcard is unreachable`() {
        assertEquals(
            1,
            warnings(
                """
                $optionEnum
                set o = Option.None
                match o {
                    _ -> { }
                    Option.None -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `arm after a binding is unreachable`() {
        assertEquals(
            1,
            warnings(
                """
                $optionEnum
                set o = Option.None
                match o {
                    z -> { }
                    Option.None -> { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `nested duplicate arm is unreachable`() {
        assertWarningMentions(
            """
            $optionEnum
            set oo = Option.Some(Option.None)
            match oo {
                Option.Some(Option.None) -> { }
                Option.Some(Option.None) -> { }
                _ -> { }
            }
            """.trimIndent(),
            "diag.unreachable-arm",
        )
    }

    // ========== 绑定名撞变体名（告警） ==========

    @Test
    fun `binding named like a variant is warned`() {
        assertWarningMentions(
            """
            $optionEnum
            set o = Option.None
            match o {
                None -> { }
            }
            """.trimIndent(),
            "diag.binding-named-like-variant",
            "None",
            "Option",
        )
    }

    @Test
    fun `binding named like a nested variant is warned`() {
        assertWarningMentions(
            """
            $optionEnum
            set oo = Option.Some(Option.None)
            match oo {
                Option.Some(None) -> { }
                _ -> { }
            }
            """.trimIndent(),
            "diag.binding-named-like-variant",
            "None",
            "Option",
        )
    }

    @Test
    fun `binding with an unrelated name is not warned`() {
        assertEquals(
            0,
            warnings(
                """
                $optionEnum
                set o = Option.None
                match o {
                    value -> { }
                }
                """.trimIndent()
            )
        )
    }
}
