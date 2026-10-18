package mlogix.compiler.support

import arc.struct.Seq
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.SourceMap
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.symbol.SymbolTable
import mlogix.compiler.core.type.Type
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.ir.ResolutionResult
import mlogix.compiler.pipeline.CompilationContext
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * 一次完整分析的快照：源码、AST、名称解析结果、符号表与诊断。
 *
 * 测试优先使用本对象，而不是直接摸 [CompilationContext] 的字段；这样未来给
 * AnalysisResult 增加类型/计时等信息时，测试不用改。
 */
class AnalysisResult(
    val source: String,
    val sourceFile: SourceFile,
    val sourceMap: SourceMap,
    val context: CompilationContext,
    val ast: Stmt,
    val resolution: ResolutionResult,
) {
    val diagHandler get() = context.diagHandler
    val symbolTable: SymbolTable get() = resolution.symbolTable
    val errors: Seq<Diagnostic> get() = diagHandler.errors
    val warnings: Seq<Diagnostic> get() = diagHandler.warnings

    fun errorNum(): Int = diagHandler.errorNum()
    fun warningNum(): Int = diagHandler.warningNum()

    /** 符号 name 在求解后的类型。 */
    fun typeOf(name: String): Type {
        val symbol = symbolTable.all().firstOrNull { it.name == name }
        assertNotNull(symbol, "符号 `$name` 必须存在")
        return symbol!!.type
    }

    /** [span] 覆盖的源码原文；用于把 label/note 的定位直接断言成可读片段。 */
    fun textOf(span: Span): String = source.substring(span.start, span.end)
}
