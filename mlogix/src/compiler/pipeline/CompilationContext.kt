package mlogix.compiler.pipeline

import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.SourceMapManager.SourceMap
import mlogix.compiler.diagnostic.ProblemCollector

/**
 * [CompilerContext] 的具体实现。
 *
 * 每个源文件编译时创建一个新的实例（因为 [sourceMap] 是文件级别的）。
 */
class CompilationContext(
    override val problems: ProblemCollector,
    override val sourceMap: SourceMap,
    override val config: CompilerConfig,
) : CompilerContext

