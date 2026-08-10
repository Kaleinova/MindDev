package mlogix.compiler.passes.typing

import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.pass.CompilerPass
import mlogix.compiler.core.pass.PassId
import mlogix.compiler.ir.ResolutionResult

/**
 * 类型推断 Pass：将现有 [TypeInferencer]（约束生成 + 惰性求解）包装为统一 Pass 契约。
 *
 * 输入/输出均为 Resolver 产出的 [ResolutionResult]；类型推断的副作用
 * （求解结果写回 [ResolutionResult.symbolTable]、错误写入 ProblemCollector）
 * 发生在 [TypeInferencer.analyze] 内部。
 */
class TypeInferencePass(
    private val inferencer: TypeInferencer,
) : CompilerPass<ResolutionResult, ResolutionResult> {

    override val id: PassId = PassId.TYPE_INFERENCE

    override val dependencies: Set<PassId> = emptySet()

    override fun execute(input: ResolutionResult, context: CompilerContext): ResolutionResult {
        inferencer.analyze(input, context.sourceMap)
        return input
    }
}
