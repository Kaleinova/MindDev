package mlogix.compiler.core

import arc.struct.IntSet
import arc.struct.Seq
import mlogix.compiler.core.type.Type
import mlogix.compiler.core.type.TypeScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * TypeScheme 的实例化 / 泛化：HM 类型系统的核心纯函数。
 *
 * 这里不经过 Parser/Resolver，直接构造类型树，失败时能直接定位到实例化逻辑，
 * 而不是被端到端用例的错误计数掩盖。
 */
class TypeSchemeTest {

    @Test
    fun `instantiate replaces every quantified variable consistently`() {
        val scheme = TypeScheme(
            Seq.with(Type.Var(0), Type.Var(1)),
            Type.Func(Seq.with(Type.Var(0)), Type.Var(1)),
        )

        var next = 10
        val result = scheme.instantiate { Type.Var(next++) }

        assertEquals(Type.Func(Seq.with(Type.Var(10)), Type.Var(11)), result)
        assertEquals(12, next, "每个量化变量只应生成一个新鲜变量")
    }

    @Test
    fun `instantiate with no quantified variables does not allocate`() {
        val scheme = TypeScheme(Seq(0), Type.Con("Int"))
        assertEquals(Type.Con("Int"), scheme.instantiate { error("不应生成新鲜变量") })
    }

    @Test
    fun `instantiateWith uses explicit args and fresh vars for the rest`() {
        val scheme = TypeScheme(
            Seq.with(Type.Var(0), Type.Var(1)),
            Type.Func(Seq.with(Type.Var(0), Type.Var(1)), Type.Var(0)),
        )

        var next = 20
        val result = scheme.instantiateWith(Seq.with(Type.Con("Int"))) { Type.Var(next++) }

        assertEquals(Type.Func(Seq.with(Type.Con("Int"), Type.Var(20)), Type.Con("Int")), result)
    }

    @Test
    fun `instantiateWith ignores extra args`() {
        val scheme = TypeScheme(Seq.with(Type.Var(0)), Type.Var(0))
        val result = scheme.instantiateWith(Seq.with(Type.Con("Int"), Type.Con("Str"))) {
            error("多余实参不应触发新鲜变量")
        }
        assertEquals(Type.Con("Int"), result)
    }

    @Test
    fun `generalize skips variables owned by the environment`() {
        val body = Type.Func(Seq.with(Type.Var(0)), Type.Var(1))
        val scheme = TypeScheme(Seq(0), body)
        val envFreeVars = IntSet()
        envFreeVars.add(0)

        val generalized = scheme.generalize(envFreeVars)

        assertEquals(1, generalized.typeVars.size)
        assertEquals(1, generalized.typeVars.get(0).index)
        assertEquals(body, generalized.body)
    }

    @Test
    fun `freeTypeVars deduplicates by index`() {
        val scheme = TypeScheme(
            Seq(0),
            Type.Func(Seq.with(Type.Var(4), Type.Var(4)), Type.Var(4)),
        )

        val variables = scheme.freeTypeVars()
        assertEquals(1, variables.size)
        assertEquals(4, variables.get(0).index)
    }
}
