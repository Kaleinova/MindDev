package mlogix.compiler

import arc.files.Fi
import arc.util.I18NBundle.createBundle
import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.SourceFile
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * 类型不匹配的诊断定位精度：label 应落在真正出错的**最小片段**上
 * （`Option<Int>` 里的 `Int`、实参 `"s"`），而不是整个注解 / 整个实参表达式。
 *
 * 机制见 [mlogix.compiler.core.type.TypeOrigin]：与类型结构对齐的来源树随约束进入求解器，
 * 合一递归下钻时把 label 逐层收窄；来源缺失或结构漂移时按原 span 就近退化。
 */
class DiagnosticSpanTest {
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

    /** 解析 → 名称解析 → 类型推断；返回源码本身（span 是源码字符偏移，可直接切片断言） */
    private fun analyze(source: String): String {
        context.diagHandler.clear()
        val sourceFile = SourceFile(source)
        val ast = parser.parse(sourceFile)
        val result = resolver.resolve(ast, sourceFile)
        inferencer.analyze(result, sourceFile)
        return source
    }

    /** 断言唯一那条错误的两个 label 分别恰好覆盖 [useText]（使用方/实际）与 [declText]（声明方/期望） */
    private fun assertLabels(source: String, useText: String, declText: String) {
        assertEquals(1, context.diagHandler.errorNum(), "本用例应只报一个错误")
        val error = context.diagHandler.errors[0]
        assertEquals(2, error.labels.size, "应有使用方与声明方两个 label")
        assertEquals(useText, textOf(source, error.labels[0].span), "使用方 label")
        assertEquals(declText, textOf(source, error.labels[1].span), "声明方 label")
    }

    private fun textOf(source: String, span: Span): String = source.substring(span.start(), span.end())

    // ========== 显式类型实参数量不匹配：label 与 note ==========

    @Test
    fun `enum type argument count mismatch labels the type expression and notes the declaration`() {
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            set a = Option<Int, Str>.Some(1)
            """.trimIndent()
        )
        assertEquals(1, context.diagHandler.errorNum())
        val error = context.diagHandler.errors[0]
        // label 只圈住写出类型实参的 `Option<Int, Str>`，不含 `.Some`
        assertEquals("Option<Int, Str>", textOf(source, error.labels[0].span), "使用方 label")

        // note 指向泛型定义处，并说明声明了几个什么类型参数
        val note = error.suggestions[0] as Diagnostic.Note
        assertEquals(
            I18N.bundle.format("diag.explicit-type-arg-count.note", "Option", 1, "`T`"),
            note.text,
        )
        assertEquals("Option", textOf(source, note.labels[0].span), "note 应指向枚举名")
    }

    @Test
    fun `extra type arguments are labeled and offered for removal`() {
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            set a = Option<Int, Str>.Some(1)
            """.trimIndent()
        )
        assertEquals(1, context.diagHandler.errorNum())
        val error = context.diagHandler.errors[0]
        // 主 label 圈类型表达式；具体是哪个实参多余由 help 的删除标记指出
        assertEquals(1, error.labels.size)
        assertEquals("Option<Int, Str>", textOf(source, error.labels[0].span))

        // help 是可直接照着改的删除建议：`Str` 被标成 `-`
        val help = error.suggestions[1] as Diagnostic.Help
        assertEquals(I18N.bundle.format("diag.explicit-type-arg-count.remove-extra", 1), help.text)
        assertEquals(1, help.labels.size)
        assertEquals(Diagnostic.LabelStyle.Delete, help.labels[0].style)
        assertEquals("Str", textOf(source, help.labels[0].span))
    }

    @Test
    fun `function type argument count mismatch also notes the declaration`() {
        val source = analyze(
            """
            fn id<T>(x: T) -> T { return x }
            set e = id<Int, Str>(1)
            """.trimIndent()
        )
        assertEquals(1, context.diagHandler.errorNum())
        val error = context.diagHandler.errors[0]
        assertEquals("id<Int, Str>", textOf(source, error.labels[0].span), "使用方 label")

        val note = error.suggestions[0] as Diagnostic.Note
        assertEquals(
            I18N.bundle.format("diag.explicit-type-arg-count.note", "id", 1, "`T`"),
            note.text,
        )
        assertEquals("id", textOf(source, note.labels[0].span), "note 应指向函数名")
    }

    // ========== 泛型枚举载荷 × 已声明枚举类型 ==========

    @Test
    fun `declared generic enum payload mismatch points at the type argument`() {
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            fn unwrap(x: Option<Int>) -> Int { return 0 }
            set r = unwrap(Option.Some("s"))
            """.trimIndent()
        )
        assertLabels(source, "\"s\"", "Int")
        // label 文本与顺序不变：labels[0]=实际类型（使用方），labels[1]=期望类型（声明方）
        val error = context.diagHandler.errors[0]
        assertEquals(I18N.bundle.format("diag.actual-type", "Str"), error.labels[0].text)
        assertEquals(I18N.bundle.format("diag.expected-type", "Int"), error.labels[1].text)
    }

    @Test
    fun `nested generic payload mismatch descends to the innermost write`() {
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            fn unwrap(x: Option<Option<Int>>) -> Int { return 0 }
            set r = unwrap(Option.Some(Option.Some("s")))
            """.trimIndent()
        )
        assertLabels(source, "\"s\"", "Int")
    }

    @Test
    fun `generic function parameter annotation also narrows the declaration label`() {
        // 泛型函数的形参类型可能不是类型变量（`x: Option<Int>`），来源按下标登记才能覆盖
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            fn f<U>(x: Option<Int>, y: U) -> U { return y }
            set r = f(Option.Some("s"), 1)
            """.trimIndent()
        )
        assertLabels(source, "\"s\"", "Int")
    }

    // ========== 非泛型枚举载荷 ==========

    @Test
    fun `non generic variant payload mismatch keeps precise labels`() {
        val source = analyze(
            """
            enum Shape {
                Circle(Num)
            }
            set c = Shape.Circle("s")
            """.trimIndent()
        )
        assertLabels(source, "\"s\"", "Num")
    }

    @Test
    fun `struct variant field mismatch points at the declaring field type`() {
        val source = analyze(
            """
            enum Shape {
                Rect { width: Int, height: Str }
            }
            set r = Shape.Rect(2, 1)
            """.trimIndent()
        )
        assertLabels(source, "1", "Str")
    }

    // ========== 返回值与数组元素 ==========

    @Test
    fun `return mismatch points at the returned expression`() {
        // 注意返回值类型注解要写成 `-> r : Int`（带名字的注解形式）
        val source = analyze(
            """
            fn f() -> r : Int { return "s" }
            """.trimIndent()
        )
        assertLabels(source, "\"s\"", "Int")
    }

    @Test
    fun `declared array element type is pointed at even for array literals`() {
        // 期望类型 `Array<Int>` 下推后，**使用方**也精确到出错的那个元素（此前只能指整个数组字面量）
        val source = analyze(
            """
            fn f(xs: Array<Int>) -> Int { return 0 }
            set r = f({"a"})
            """.trimIndent()
        )
        assertLabels(source, "\"a\"", "Int")
    }

    @Test
    fun `array element mismatch points at the offending element`() {
        val source = analyze(
            """
            set a : Array<Int> = {1, "x"}
            """.trimIndent()
        )
        assertLabels(source, "\"x\"", "Int")
    }

    @Test
    fun `nested array of enum payload is checked at the innermost literal`() {
        // 数组元素期望 → 变体构造器采用期望的枚举类型 → 载荷期望：三层下推落到 `"s"`
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            set a : Array<Option<Int>> = {Option.Some("s")}
            """.trimIndent()
        )
        assertLabels(source, "\"s\"", "Int")
    }

    @Test
    fun `return value is checked in place`() {
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            fn f() -> r : Option<Int> { return Option.Some("s") }
            """.trimIndent()
        )
        assertLabels(source, "\"s\"", "Int")
    }

    @Test
    fun `generic parameters are not pushed across call sites`() {
        // 泛型函数的 `T` 跨调用点共享：期望类型绝不能下推，否则两次调用会互相污染
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            fn pick<T>(x: Option<T>, fallback: T) -> r : T { return fallback }
            set a = pick(Option.Some(1), 2)
            set b = pick(Option.Some("s"), "t")
            """.trimIndent()
        )
        assertEquals(0, context.diagHandler.errorNum())
    }

    @Test
    fun `payload argument that ignores the expectation is still checked`() {
        // 表达式（二元运算）不消费期望类型：必须退回粗约束，否则会漏检
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            set a : Option<Int> = Option.Some(1 + 2.0)
            """.trimIndent()
        )
        assertEquals(1, context.diagHandler.errorNum())
        assertEquals("Int", textOf(source, context.diagHandler.errors[0].labels[1].span))
    }

    @Test
    fun `payload positions that cannot be mapped to a type argument are still checked`() {
        // `P(Array<T>)` 里 T 嵌在更深层：无法下推，靠枚举类型实参兜底约束报错
        val source = analyze(
            """
            enum E<T> {
                P(Array<T>)
            }
            set e : E<Int> = E.P({"x"})
            """.trimIndent()
        )
        assertEquals(1, context.diagHandler.errorNum())
        assertEquals("Int", textOf(source, context.diagHandler.errors[0].labels[1].span))
    }

    // ========== set 的类型注解 ==========

    @Test
    fun `set annotation is checked against the initial value`() {
        val source = analyze(
            """
            set a : Int = "s"
            """.trimIndent()
        )
        assertLabels(source, "\"s\"", "Int")
    }

    @Test
    fun `set annotation with a generic enum narrows to the type argument`() {
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            set a : Option<Int> = Option.Some("s")
            """.trimIndent()
        )
        assertLabels(source, "\"s\"", "Int")
    }

    @Test
    fun `set annotation with an array narrows to the element type`() {
        val source = analyze(
            """
            set a : Array<Int> = {"x"}
            """.trimIndent()
        )
        assertLabels(source, "\"x\"", "Int")
    }

    @Test
    fun `set annotation without initializer constrains later assignments`() {
        val source = analyze(
            """
            set a : Int
            a = "s"
            """.trimIndent()
        )
        assertEquals(1, context.diagHandler.errorNum())
        assertEquals("\"s\"", textOf(source, context.diagHandler.errors[0].labels[0].span))
    }

    // ========== 退化路径 ==========

    @Test
    fun `missing type argument source degrades to the type expression`() {
        // 裸 `Option` 注解没有类型实参可指：声明方退化为 `Option` 本身，绝不报错或变宽
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            enum Other {
                Blue
            }
            fn f(x: Option) -> Int { return 0 }
            set r = f(Other.Blue)
            """.trimIndent()
        )
        assertEquals(1, context.diagHandler.errorNum())
        val error = context.diagHandler.errors[0]
        assertEquals("Option", textOf(source, error.labels[1].span))
    }

    @Test
    fun `call through an alias keeps the annotated span when origins are unavailable`() {
        // 经变量间接调用时拿不到形参来源（不是具名函数）：声明方退回注解 span（既有行为），
        // 使用方仍因实参自身的来源树而下钻到 `"s"`
        val source = analyze(
            """
            enum Option<T> {
                None
                Some(T)
            }
            fn unwrap(x: Option<Int>) -> Int { return 0 }
            set g = unwrap
            set r = g(Option.Some("s"))
            """.trimIndent()
        )
        assertLabels(source, "\"s\"", "x: Option<Int>")
    }
}
