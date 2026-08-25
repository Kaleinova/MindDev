package mlogix.compiler.pipeline

import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.pass.CompilerPass

/**
 * 类型安全的管道：将输入类型 [I] 变换为输出类型 [O]。
 */
interface Pipeline<in I, out O> {
    fun execute(input: I, context: CompilerContext): O

    /**
     * 组合两个管道：当前管道输出 [O]，下一个管道输入 [O] 并输出 [P]，
     * 结果形成从 [I] 到 [P] 的新管道。
     */
    fun <P> then(next: Pipeline<O, P>): Pipeline<I, P> = Pipeline { input, ctx ->
        val mid = this.execute(input, ctx)
        next.execute(mid, ctx)
    }

    companion object {
        /**
         * 从单个 [mlogix.compiler.core.pass.CompilerPass] 创建 Pipeline。
         */
        fun <I, O> from(pass: CompilerPass<I, O>): Pipeline<I, O> = Pipeline(pass::execute)
    }
}

/**
 * 便捷构造器，用于从 lambda 创建 Pipeline。
 */
inline fun <I, O> Pipeline(crossinline block: (I, CompilerContext) -> O): Pipeline<I, O> =
    object : Pipeline<I, O> {
        override fun execute(input: I, context: CompilerContext): O = block(input, context)
    }