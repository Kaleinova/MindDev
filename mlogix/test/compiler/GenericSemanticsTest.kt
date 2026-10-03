package mlogix.compiler

import arc.files.Fi
import arc.struct.Seq
import arc.util.I18NBundle.createBundle
import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.symbol.SymbolTable
import mlogix.compiler.core.type.BuiltinType
import mlogix.compiler.core.type.Type
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.passes.parsing.Lexer
import mlogix.compiler.passes.parsing.Parser
import mlogix.compiler.passes.resolution.Resolver
import mlogix.compiler.passes.typing.TypeInferencer
import mlogix.compiler.pipeline.CompilationContext
import mlogix.util.I18N
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * 泛型语义（Resolver + TypeInferencer + TypeSolver）端到端测试：
 * 解析 → 名称解析 → 类型推断，断言错误数。
 */
class GenericSemanticsTest {
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

    /** 解析 → 名称解析 → 类型推断，返回推断后的错误数（parser.parse 会先清空诊断） */
    private fun analyze(source: String): Int {
        analyzeSymbols(source)
        return context.diagHandler.errorNum()
    }

    /** 解析 → 名称解析 → 类型推断，返回推断后的符号表（诊断仍记在 context.diagHandler） */
    private fun analyzeSymbols(source: String): SymbolTable {
        context.diagHandler.clear()
        val sourceFile = SourceFile(source)
        val ast = parser.parse(sourceFile)
        val result = resolver.resolve(ast, sourceFile)
        inferencer.analyze(result, sourceFile)
        return result.symbolTable
    }

    /** 求解后符号 [name] 的类型 */
    private fun typeOf(table: SymbolTable, name: String): Type {
        val symbol = table.all().firstOrNull { it.name == name }
        assertNotNull(symbol, "符号 `$name` 必须存在")
        return symbol!!.type
    }

    // ========== 正例：0 错误 ==========

    @Test
    fun `generic identity is polymorphic across calls`() {
        assertEquals(
            0,
            analyze(
                """
                fn id<T>(x: T) -> T { return x }
                set a = id(42)
                set b = id("hello")
                """.trimIndent()
            )
        )
    }

    @Test
    fun `multiple type params with explicit arguments`() {
        assertEquals(
            0,
            analyze(
                """
                fn pair<T, E>(x: T, y: E) -> T { return x }
                set p = pair<Int, Str>(1, "s")
                set q = pair(2, 3)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `nested generic type arguments with array param`() {
        assertEquals(
            0,
            analyze(
                """
                fn first<T>(xs: Array<T>) -> T { return xs[0] }
                set n = first({1, 2, 3})
                set s = first({"a", "b"})
                set m = first<Array<Int>>({{1}, {2}})
                """.trimIndent()
            )
        )
    }

    @Test
    fun `generic function reference with turbofish args`() {
        assertEquals(
            0,
            analyze(
                """
                fn id<T>(x: T) -> T { return x }
                set f = id<Int>
                set c = f(7)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `type param in return annotation`() {
        assertEquals(
            0,
            analyze(
                """
                fn wrap<T>(x: T) -> r : Array<T> { return ({x}) }
                set arr = wrap<Int>(1)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `nested generic functions referencing outer type param`() {
        assertEquals(
            0,
            analyze(
                """
                fn outer<T>(x: T) -> T {
                    fn inner<U>(y: U, z: T) -> T { return z }
                    return inner<Str>("s", x)
                }
                set o = outer(5)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `array element type is checked through generic app`() {
        assertEquals(
            1,
            analyze(
                """
                fn first<T>(xs: Array<T>) -> T { return xs[0] }
                set s = first({"a"})
                fn get(xs: Array<Int>) -> Int { return xs[0] }
                set bad = get({"a"})
                """.trimIndent()
            )
        )
    }

    @Test
    fun `variable type propagates through set chain`() {
        // `set b = a` 必须能看到 a 已推断为 Int（回归：SetVar 曾因去重推断而丢失快速传播）
        assertEquals(
            1,
            analyze(
                """
                fn f(x: Str) -> Str { return x }
                set a = 1
                set b = a
                set r = f(b)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `generic array element type propagates through set chain`() {
        assertEquals(
            0,
            analyze(
                """
                fn id<T>(x: T) -> T { return x }
                set a = id(42)
                set b = a
                set c = b
                """.trimIndent()
            )
        )
    }

    /**
     * 符号类型必须是求解终态：函数符号在 walk 阶段挂的是 `(Var(k)) -> Var(m)`（形参/返回值
     * 各是一个待求解变量），求解后应写回 `(Int) -> Int`。
     * 回归：旧实现只把「类型本身恰好是变量」的符号写回，函数这类复合类型会被整体跳过。
     *
     * 返回类型写成**具名注解**（`-> r : Int`）：裸写 `-> Int` 当前不会被识别为返回值类型声明
     * （返回值类型只由 `return` 表达式推断出来），用它会让这个回归测试耦合到那个问题上。
     */
    @Test
    fun `solved signature is written back to the function symbol`() {
        val table = analyzeSymbols("fn f(x: Int) -> r : Int { return x }")
        assertEquals(Type.Func(Seq.with(BuiltinType.Int), BuiltinType.Int), typeOf(table, "f"))
    }

    @Test
    fun `generic function symbol keeps its quantified type params`() {
        // 泛型函数符号的类型保持含**量化**类型参数的签名（`(T) -> T`），不能被误当成具体类型替换掉
        val table = analyzeSymbols(
            """
            fn id<T>(x: T) -> r : T { return x }
            set a = id(42)
            set b = id("hello")
            """.trimIndent()
        )
        val signature = typeOf(table, "id") as? Type.Func
        assertNotNull(signature, "泛型函数符号的类型应当是函数类型")
        assertEquals(1, signature!!.params.size)
        assertTrue(
            signature.params.get(0) is Type.Var,
            "泛型函数的形参应保持量化的类型参数，而不是被替换成某个调用点的具体类型",
        )
        assertEquals(signature.params.get(0), signature.result)
    }

    // ========== 反例：预期错误 ==========

    @Test
    fun `explicit type argument count mismatch`() {
        assertEquals(
            1,
            analyze(
                """
                fn id<T>(x: T) -> T { return x }
                set e = id<Int, Str>(1)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `type arguments on non generic function`() {
        assertEquals(
            1,
            analyze(
                """
                fn plain(x: Int) -> Int { return x }
                set e = plain<Str>(1)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `declaration site nested type params are not supported`() {
        assertEquals(
            1,
            analyze(
                """
                fn hkt<T, E<U>>(x: T) -> T { return x }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `type param used as value is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                fn bad<T>(x: T) -> T { set t = T; return x }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `variable declaration with type args is rejected`() {
        // Resolver：变量不能携带类型实参；TypeInferencer：0 个类型参数却传入 1 个
        assertEquals(
            2,
            analyze(
                """
                set v<Int> = 1
                """.trimIndent()
            )
        )
    }

    @Test
    fun `non generic type with type arguments in annotation`() {
        assertEquals(
            1,
            analyze(
                """
                fn bad(x: Int<Int>) -> Int { return x }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `type argument with type param head is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                fn bad<E>(x: E) -> E {
                    return bad<E<Int>>({{1}})
                }
                """.trimIndent()
            )
        )
    }
}
