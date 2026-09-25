package mlogix.compiler.core.type

import mlogix.compiler.core.token.TokenType

/**
 * 内置类型常量与字面量 → 类型的映射。
 *
 * 说明：
 * - 所有内置类型都是 [Type.Con]，结构相等，可直接用 `==` 比较；
 * - [toType] **绝不抛异常**（分析代码无 throw 铁律），未知字面量类型返回 [Type.Error]；
 * - [Unknown] / [Error] 是 [Type.Unknown] / [Type.Error] 的别名，便于调用处语义清晰。
 */
object BuiltinType {
    val Num: Type.Con = Type.Con("Num")
    val Int: Type.Con = Type.Con("Int")
    val Str: Type.Con = Type.Con("Str")
    val Bool: Type.Con = Type.Con("Bool")
    val Null: Type.Con = Type.Con("Null")

    /** 数组类型名（类型注解 `: Array` 用）；推断出的数组是 [Type.Arr] */
    val Array: Type.Con = Type.Con("Array")
    val Fn: Type.Con = Type.Con("Fn")
    val Ref: Type.Con = Type.Con("Ref")

    /** 未定类型（尚未被约束） */
    val Unknown: Type = Type.Unknown

    /** 错误类型（诊断已报告，抑制级联错误） */
    val Error: Type = Type.Error

    /**
     * 将给定的 TokenType 转换为对应的 Type。
     *
     * 注意 `NUM` / `COL` 都必须有类型：一旦返回 [Type.Error]，求解器会静默通过
     * （见 [mlogix.compiler.passes.typing.TypeSolver]），等于这些字面量在 `Num` 位置上完全不检查。
     * 颜色值按语言文档「底层上是 `Num`」也归入 [Num]；若将来引入独立的 `Col` 类型，改这里即可。
     *
     * @return 已知字面量返回对应类型；未知 token 返回 [Type.Error]（绝不抛异常）
     */
    fun toType(tokenType: TokenType): Type {
        return when (tokenType) {
            TokenType.INT -> Int
            // 浮点字面量（`1.5`、`1e3`）与颜色字面量都属浮点族
            TokenType.NUM, TokenType.COL -> Num
            TokenType.TRUE, TokenType.FALSE -> Bool
            TokenType.NULL -> Null
            TokenType.STR -> Str
            TokenType.FN -> Fn
            TokenType.UNKNOWN -> Unknown
            else -> Error
        }
    }
}
