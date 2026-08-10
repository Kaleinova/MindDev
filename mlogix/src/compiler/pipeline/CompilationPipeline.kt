package mlogix.compiler.pipeline

import arc.struct.Seq
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.SourceMapManager.SourceMap
import mlogix.compiler.core.pass.CompilerPass

/**
 * 编译管道编排器：按顺序执行一组 [CompilerPass]。
 *
 * 管道的起点是 [SourceMap]：第一个 Pass（ParsingPass）负责词法+语法分析，
 * 输出原始 AST（[Stmt]），随后 ResolutionPass 产出 [mlogix.compiler.ir.ResolutionResult]，
 * TypeInferencePass 在其上继续变换。
 * 未来引入 HIR / TypedHir 时，只需替换 Pass 列表中的实现，
 * 不需要修改已有 Pass 代码（开闭原则）。
 *
 * Pass 之间通过 IR 数据传递、通过 [CompilerContext] 共享诊断状态，
 * 绝不互相引用。
 */
class CompilationPipeline(
    private val passes: Seq<CompilerPass<*, *>>,
) {

    /**
     * 对单个源文件执行完整管道。
     *
     * @param input 管道的输入（当前为 [SourceMap]，未来可以是 RawAst/Hir）
     * @param context 当前文件的编译上下文
     * @return 管道最终产出的 IR（当前为 [mlogix.compiler.ir.ResolutionResult]）
     */
    fun run(input: Any, context: CompilerContext): Any {
        var ir: Any = input
        for (pass in passes) {
            @Suppress("UNCHECKED_CAST")
            ir = (pass as CompilerPass<Any, Any>).execute(ir, context)
        }
        return ir
    }
}
