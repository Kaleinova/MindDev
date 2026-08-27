package mlogix.compiler.pipeline

import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.diagnostic.DiagHandler

/**
 * [CompilerContext] 的具体实现。
 */
class CompilationContext(
    override val diagHandler: DiagHandler,
    override val config: CompilerConfig,
) : CompilerContext

