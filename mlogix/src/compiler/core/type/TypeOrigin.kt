package mlogix.compiler.core.type

import arc.struct.Seq
import mlogix.compiler.core.span.Span

/**
 * 与 [Type] 结构对齐的「来源位置」树：记录某个类型（子）项在源码里写在哪里。
 *
 * 用途：约束求解器递归合一失败时，用它下钻到真正出错的那一层，把 label 指到**最小片段**
 * （如 `Option<Int>` 里的 `Int`、实参 `"s"`），而不是整个 `x: Option<Int>` / `Option.Some("s")`。
 *
 * 设计约定：
 * - 它**不挂在** [Type] 上（类型保持纯数据 DAG，见 [Type] 的文档），
 *   只在约束与推断结果中随类型一路传递，纯诊断用途，不参与类型相等与求解语义；
 * - 子项下标约定（生产者与求解器必须一致）：
 *   - [Type.Func]：形参依次 `0..n-1`，返回值是 `n`；
 *   - [Type.App]：[Type.App.args] 同序；
 *   - [Type.Arr]：唯一子项 = 元素（`0`）；
 *   - [Type.TupleType]：[Type.TupleType.elements] 同序；
 *   - [Type.Con] / [Type.Var] / [Type.Unknown] / [Type.Error]：无子项。
 *
 * @param span 该（子）项在源码中的位置；未知时为 `null`（求解器就近退化到上一层的位置）
 * @param children 各子项的来源，顺序同子项下标约定；不知道的子项可以直接不写（下钻会退化为 null）
 */
data class TypeOrigin(val span: Span?, val children: Seq<TypeOrigin> = Seq(0)) {

    /**
     * 取第 [index] 个子项的来源；结构与类型不符（越界 / 未记录）时返回 `null`，
     * 由调用方沿用上一层的位置——**绝不抛异常**。
     */
    fun childAt(index: Int): TypeOrigin? {
        if (index < 0 || index >= children.size) return null
        return children.get(index)
    }

    companion object {
        /**
         * 无来源占位：需要保持子项下标对齐、但该子项在源码里没有对应写法时使用
         * （如 `enum E<A, B> { N(B) }` 的 `N` 只对应 `B`，`A` 没有实参可指）。
         *
         * 它 [span] 为 `null`、无子项，下钻到它时求解器保持上一层的定位，等价于「这里更精确不了」。
         */
        val Unknown: TypeOrigin = TypeOrigin(null)
    }
}
