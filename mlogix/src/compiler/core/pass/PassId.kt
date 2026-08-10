package mlogix.compiler.core.pass

/**
 * 编译通行证的唯一标识。
 *
 * 未来用于：
 * - 增量编译的缓存 key（输入哈希 + PassId -> 缓存结果）
 * - 构建 Pass 之间的依赖图（增量重编译）
 */
enum class PassId {
    /** 词法+语法分析（一个 Pass 完成，Parser 持有 Lexer 按需扫描 Token） */
    PARSE,

    RESOLUTION,
    DESUGAR,
    TYPE_INFERENCE,
    DATAFLOW,
    EXHAUSTIVENESS,
}
