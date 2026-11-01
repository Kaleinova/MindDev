package mlogix.compiler.semantics

import arc.struct.Seq
import mlogix.compiler.core.type.BuiltinType
import mlogix.compiler.core.type.Type
import mlogix.compiler.support.CompilerTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * 返回值声明的端到端测试（解析 → 名称解析 → 类型推断）：
 * - 裸写形式 `-> Int` / `-> T` / `-> Array<Int>` / `?` 与具名形式 `-> r : Int` 等价；
 * - 多个返回值建模为**元组**（`-> Int, Str` ≡ `(Int, Str)`），名字不进入类型；
 * - 断言求解后符号类型，确认声明真的参与了约束（而不只是错误计数）。
 */
class ReturnTest : CompilerTestBase() {

    /** `(T1, T2, ...)` */
    private fun tupleOf(vararg elements: Type): Type = Type.TupleType(Seq.with(*elements))

    /** `(...) -> result` */
    private fun funcOf(result: Type, vararg params: Type): Type = Type.Func(Seq.with(*params), result)

    // ========== 裸写返回值注解 ==========

    @Test
    fun `bare return annotation is recognized and enforced`() {
        // 回归：此前 `-> Int` 不是 Expr.Annotation，返回值注解被整体忽略，这里不报错
        assertEquals(1, analyze("""fn f() -> Int { return "s" }"""))
    }

    @Test
    fun `bare return annotation types the function symbol`() {
        val table = analyzeSymbols("""fn f() -> Int { return 1 }""")
        assertEquals(funcOf(BuiltinType.Int), typeOf(table, "f"))
    }

    @Test
    fun `bare return annotation on a method is recognized`() {
        val table = analyzeSymbols(
            """
            struct Point {
                x : Num
                fn get() -> Num { return x }
            }
            """.trimIndent()
        )
        assertEquals(funcOf(BuiltinType.Num, Type.Con("Point")), typeOf(table, "get"))
    }

    /**
     * 裸写的**类型参数**返回值（`-> T`）此前完全没接进签名：泛型函数的返回值是一个额外的
     * 自由变量，被量化后每个调用点实例化出**独立**变量，形参与返回值的联系丢失
     * （`set a = id(42)` 的 `a` 停在未绑定变量）。现在 `-> T` 就是形参那个 T。
     */
    @Test
    fun `bare type param return annotation ties parameter and result`() {
        val table = analyzeSymbols(
            """
            fn id<T>(x: T) -> T { return x }
            set a = id(42)
            set b = id("hello")
            """.trimIndent()
        )
        val signature = typeOf(table, "id") as? Type.Func
        assertNotNull(signature, "函数符号的类型应当是函数类型")
        assertEquals(signature!!.params.get(0), signature.result, "形参与返回值必须是同一个类型参数")
        assertEquals(BuiltinType.Int, typeOf(table, "a"))
        assertEquals(BuiltinType.Str, typeOf(table, "b"))
    }

    @Test
    fun `nullable return marker means the Null type`() {
        // `?` 在解析期被合成为 `Null` 标识符；名称解析与类型转换必须同样覆盖它
        val table = analyzeSymbols("""fn f() -> ? { return null }""")
        assertEquals(funcOf(BuiltinType.Null), typeOf(table, "f"))
        assertEquals(1, analyze("""fn f() -> ? { return 1 }"""))
    }

    @Test
    fun `nested generic return type is resolved`() {
        val table = analyzeSymbols("""fn f(x: Int) -> Array<Array<Int>> { return {{x}} }""")
        val expected = Type.App(
            BuiltinType.Array,
            Seq.with(Type.App(BuiltinType.Array, Seq.with(BuiltinType.Int))),
        )
        assertEquals(funcOf(expected, BuiltinType.Int), typeOf(table, "f"))
    }

    // ========== 多返回值 = 元组 ==========

    @Test
    fun `multiple unnamed results are a tuple`() {
        val table = analyzeSymbols("""fn pair() -> Int, Str { return (1, "s") }""")
        assertEquals(funcOf(tupleOf(BuiltinType.Int, BuiltinType.Str)), typeOf(table, "pair"))
    }

    @Test
    fun `multiple named results keep the same structural tuple type`() {
        // 名字不进入类型：具名与裸写产生**同一个**结构化类型，二者可互相替换
        val table = analyzeSymbols("""fn pair() -> a: Int, b: Str { return (1, "s") }""")
        assertEquals(funcOf(tupleOf(BuiltinType.Int, BuiltinType.Str)), typeOf(table, "pair"))
    }

    @Test
    fun `returned tuple is accepted where a tuple parameter is declared`() {
        // `p: (Int, Str)` 是单一元组注解；多返回值元组必须与它结构相等
        assertEquals(
            0,
            analyze(
                """
                fn pair() -> Int, Str { return (1, "s") }
                fn take(p: (Int, Str)) -> Int { return 0 }
                set r = take(pair())
                """.trimIndent()
            )
        )
    }

    @Test
    fun `element type mismatch points at the wrong element`() {
        val source = """fn pair() -> Int, Str { return (1, 2) }"""
        assertEquals(1, analyze(source))
        val error = context.diagHandler.errors[0]
        assertEquals("2", textOf(source, error.labels[0].span), "使用方 label 应指向写错的那个分量")
    }

    @Test
    fun `element count mismatch is rejected`() {
        assertEquals(1, analyze("""fn pair() -> Int, Str { return (1, 2, 3) }"""))
    }

    @Test
    fun `generic multiple results are instantiated per call site`() {
        val table = analyzeSymbols(
            """
            fn dup<T>(x: T) -> T, T { return (x, x) }
            set a = dup(1)
            set b = dup("s")
            """.trimIndent()
        )
        assertEquals(tupleOf(BuiltinType.Int, BuiltinType.Int), typeOf(table, "a"))
        assertEquals(tupleOf(BuiltinType.Str, BuiltinType.Str), typeOf(table, "b"))
        val signature = typeOf(table, "dup") as? Type.Func
        assertNotNull(signature, "函数符号的类型应当是函数类型")
        val result = signature!!.result as? Type.TupleType
        assertNotNull(result, "多返回值的结果类型应当是元组")
        assertEquals(result!!.elements.get(0), result.elements.get(1), "两个分量都应是形参那个类型参数")
        assertEquals(signature.params.get(0), result.elements.get(0))
    }

    @Test
    fun `multiple results on a method are a tuple`() {
        val table = analyzeSymbols(
            """
            struct Point {
                x : Num
                fn pair() -> Int, Str { return (1, "s") }
            }
            """.trimIndent()
        )
        assertEquals(
            funcOf(tupleOf(BuiltinType.Int, BuiltinType.Str), Type.Con("Point")),
            typeOf(table, "pair"),
        )
    }
}
