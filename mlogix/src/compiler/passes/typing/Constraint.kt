package mlogix.compiler.passes.typing

import mlogix.compiler.core.type.Type
import mlogix.compiler.core.span.Span

sealed class Constraint(open val pos: Span?) {

    /**
     * 相等约束：t1 必须等于 t2。
     *
     * 位置约定（用于不匹配报错的定位）：
     * - [pos]：**使用方**位置——约束产生处（实际类型的来源位置），报错时用 [Problem.point] 标记；
     * - [declPos]：**声明方**位置——期望类型（[t2]）的声明处，报错时用 [Problem.info] 标记；
     *   当 [t2] 无声明来源（如内置 Bool、合成的函数类型）时为 null。
     *
     * @param t1 使用方类型（实际类型）
     * @param t2 声明方类型（期望类型）
     */
    data class Equal(
        val t1: Type,
        val t2: Type,
        override val pos: Span? = null,
        val declPos: Span? = null,
    ) : Constraint(pos)

    data class Subtype(val sub: Type, val sup: Type, override val pos: Span? = null) : Constraint(pos)

    data class Implicit(val cls: String, val t: Type, override val pos: Span? = null) : Constraint(pos)
}

