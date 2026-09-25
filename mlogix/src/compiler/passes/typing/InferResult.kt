package mlogix.compiler.passes.typing

import arc.struct.Seq
import mlogix.compiler.core.type.Type
import mlogix.compiler.core.type.TypeOrigin

/**
 * 表达式推断结果。
 *
 * @param type 推断出的类型
 * @param constraints 该表达式引入的约束（惰性求解）
 * @param origin 该类型在源码中的来源树（见 [TypeOrigin]）；拿不到更精确信息时为 null，
 *   约束求解器会用约束自身的 pos/declPos 定位
 * @param expectedHandled 是否已经**消费**了调用方传入的期望类型（见 [ExpectedType]）：
 *   为真表示「实际类型 vs 期望类型」的比较已经在本表达式内部完成（或已由本表达式绑定），
 *   调用方不必再加同名约束，避免同一次不匹配报两遍
 */
data class InferResult(
    val type: Type,
    val constraints: Seq<Constraint>,
    val origin: TypeOrigin? = null,
    val expectedHandled: Boolean = false,
)
