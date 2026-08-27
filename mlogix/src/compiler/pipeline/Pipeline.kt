package mlogix.compiler.pipeline

import mlogix.compiler.core.SourceMap.SourceFile
import mlogix.compiler.core.pass.CompilerPass

/**
 * 类型安全的管道：将输入类型 [I] 变换为输出类型 [O]。
 */
interface Pipeline<in I, out O> {
    fun execute(input: I, sourceFile: SourceFile, timer: PhaseTimer): O

    /**
     * 组合两个管道：当前管道输出 [O]，下一个管道输入 [O] 并输出 [P]，
     * 结果形成从 [I] 到 [P] 的新管道。
     */
    fun <P> then(next: Pipeline<O, P>): Pipeline<I, P> = Pipeline { input, file, timer ->
        val mid = this.execute(input, file, timer)
        next.execute(mid, file, timer)
    }

    companion object {
        /**
         * 从单个 [mlogix.compiler.core.pass.CompilerPass] 创建 Pipeline。
         */
        fun <I, O> from(pass: CompilerPass<I, O>): Pipeline<I, O> = Pipeline { input, file, timer ->
            timer.startPhase(pass.id)
            val result = pass.execute(input, file)
            timer.endPhase()
            return@Pipeline result
        }
    }
}

/**
 * 便捷构造器，用于从 lambda 创建 Pipeline。
 */
inline fun <I, O> Pipeline(crossinline block: (I, SourceFile, PhaseTimer) -> O): Pipeline<I, O> =
    object : Pipeline<I, O> {
        override fun execute(input: I, sourceFile: SourceFile, timer: PhaseTimer): O = block(input, sourceFile, timer)
    }