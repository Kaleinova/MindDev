package mlogix.compiler.core.pass

import mlogix.compiler.core.CompilerContext

/**
 * 编译通行证（Pass）契约。
 *
 * 所有语义分析组件必须实现此接口，以便未来支持：
 * - 增量重编译（根据 [dependencies] 构建依赖图）
 * - 并行编译（多个 Pass 之间无共享可变状态）
 * - 缓存（根据 [id] 作为缓存 key）
 *
 * @param I 输入的 IR 类型
 * @param O 输出的 IR 类型（可以是副作用，输出仍为输入类型）
 */
interface CompilerPass<in I, out O> {

    /** 该 Pass 的唯一标识，未来用于缓存 key 与依赖图构建 */
    val id: PassId

    /** 该 Pass 依赖的早期 Pass 标识集合 */
    val dependencies: Set<PassId>

    /**
     * 执行该 Pass。
     *
     * 注意：Pass 内部不抛异常中断管道；错误一律通过 [CompilerContext.problems] 报告。
     * 管道继续运行，即使出现错误，也带上 ErrorType/ErrorExpr 继续。
     */
    fun execute(input: I, context: CompilerContext): O
}

