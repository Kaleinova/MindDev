package mlogix.compiler.passes.typing

import mlogix.compiler.core.span.Span
import mlogix.compiler.core.type.Type
import mlogix.compiler.core.type.TypeOrigin

sealed class Constraint(open val pos: Span?) {

    /**
     * 相等约束：t1 必须等于 t2。
     *
     * 当 [t2] 无声明来源（如内置 Bool、合成的函数类型）时为 null。
     *
     * 精度提升（[useOrigin] / [declOrigin]）：
     * - 二者是与 [t1] / [t2] 结构对齐的来源树（见 [TypeOrigin]），让求解器在递归合一到子项时
     *   把 label 收窄到出错的那一层写法（`Option<Int>` 的 `Int`、实参 `"s"`）；
     * - 为 null 或结构与类型不符时，退化为 [pos] / [declPos]（诊断定位绝不比原来更差）。
     *
     * @param t1 使用方类型（实际类型）
     * @param t2 声明方类型（期望类型）
     */
    data class Equal(
        val t1: Type,
        val t2: Type,
        override val pos: Span? = null,
        val declPos: Span? = null,
        val useOrigin: TypeOrigin? = null,
        val declOrigin: TypeOrigin? = null,
    ) : Constraint(pos)

    data class Subtype(val sub: Type, val sup: Type, override val pos: Span? = null) : Constraint(pos)

    data class Implicit(val cls: String, val t: Type, override val pos: Span? = null) : Constraint(pos)
}
