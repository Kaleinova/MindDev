package mlogix.compiler.passes.typing

import mlogix.compiler.core.span.Span
import mlogix.compiler.core.type.Type
import mlogix.compiler.core.type.TypeOrigin

/**
 * 双向检查的「期望类型」：自上而下传给表达式推断的信息
 * （对齐 rustc `rustc_hir_typeck::Expectation::ExpectHasType`）。
 *
 * 传统做法是「自下而上推断 + 事后靠 span 收窄」（见 [TypeOrigin]）。有了期望类型，
 * 错误可以在**写出错误值的那个子表达式**上就地报出：
 * `set a : Array<Int> = {"x"}` 里错的是元素 `"x"`，而不是整个数组字面量。
 *
 * 消费规则（谁负责检查）：
 * - 能**结构化消费**期望的表达式负责检查并在 [InferResult.expectedHandled] 置真
 *   （字面量/标识符就地比较、数组字面量把元素类型下推、变体构造器采用期望的枚举类型）；
 * - 不能消费的表达式（二元运算、调用结果等）忽略期望，调用方仍用原来的同名约束兜底——
 *   因此**同一次不匹配只报一次**；
 * - 期望类型里含被调用方自己量化的类型参数时，调用方**不得**下推（那是跨调用点共享的变量，
 *   下推会让两次调用的类型参数互相污染）。
 *
 * @param type 期望的类型
 * @param origin 期望类型的来源树（声明方 label；可下钻到 `Option<Int>` 的 `Int`）
 */
data class ExpectedType(val type: Type, val origin: TypeOrigin? = null) {

    /** 期望类型的声明位置（无来源时为 null，退化为只有使用方 label） */
    val span: Span? get() = origin?.span
}
