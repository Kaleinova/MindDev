package mlogix.compiler.pipeline

import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.SourceMap.SourceFile
import mlogix.compiler.diagnostic.DiagHandler

/**
 * [CompilerContext] 的具体实现。
 *
 * 每个源文件编译时创建一个新的实例（因为 [sourceFile] 是文件级别的）。
 */
class CompilationContext(
    override val problems: DiagHandler,
    override val sourceFile: SourceFile,
    override val config: CompilerConfig,
) : CompilerContext

