package mlogix.compiler.passes.resolution

import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.pass.CompilerPass
import mlogix.compiler.core.pass.PassId
import mlogix.compiler.ir.ResolutionResult

/**
 * 名称解析 Pass：将 [Resolver] 包装为统一 Pass 契约。
 *
 * 输入：原始 AST（[Stmt]）；输出：[ResolutionResult]（作用域树 + DefId 符号表）。
 * 后续 TYPE_INFERENCE 等 Pass 依赖本 Pass 的结果。
 */
class ResolutionPass(
    private val resolver: Resolver,
) : CompilerPass<Stmt, ResolutionResult> {

    override val id: PassId = PassId.RESOLUTION

    override val dependencies: Set<PassId> = emptySet()

    override fun execute(input: Stmt, context: CompilerContext): ResolutionResult {
        return resolver.resolve(input, context.sourceMap)
    }
}

