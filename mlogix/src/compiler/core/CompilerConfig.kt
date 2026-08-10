package mlogix.compiler.core

/**
 * 编译器配置。
 *
 * 当前仅保留最小字段，未来扩展：
 * - 优化级别
 * - 目标版本
 * - 增量编译开关
 */
data class CompilerConfig(
    /** 优化级别（0 = 关闭） */
    val optimize: Int = 0,
    /** 目标版本 */
    val target: String = "default",
)

