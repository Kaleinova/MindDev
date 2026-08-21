package mlogix.compiler

import arc.files.Fi
import arc.util.I18NBundle.createBundle
import mlogix.compiler.core.SourceMap.SourceFile
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.passes.parsing.Lexer
import mlogix.compiler.passes.parsing.Parser
import mlogix.compiler.passes.resolution.Resolver
import mlogix.compiler.passes.typing.TypeInferencer
import mlogix.util.I18N
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * 泛型语义（Resolver + TypeInferencer + TypeSolver）端到端测试：
 * 解析 → 名称解析 → 类型推断，断言错误数。
 */
class GenericSemanticsTest {
    private val problems = DiagHandler()
    private val lexer = Lexer(problems)
    private val parser = Parser(lexer, problems)
    private val resolver = Resolver(problems)
    private val inferencer = TypeInferencer(problems)

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
        val ast = parser.parse(source)
        val sourceFile = SourceFile(source)
        val result = resolver.resolve(ast, sourceFile)
        inferencer.analyze(result, sourceFile)
        return problems.errorNum()
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
        // `set b = a` 必须能看到 a 已推断为 Int（回归：SetVarStmt 曾因去重推断而丢失快速传播）
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
