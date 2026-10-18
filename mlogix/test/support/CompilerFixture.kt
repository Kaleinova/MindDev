package mlogix.compiler.support

import arc.struct.Seq
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.SourceMap
import mlogix.compiler.core.token.Token
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.ir.ResolutionResult
import mlogix.compiler.passes.parsing.Lexer
import mlogix.compiler.passes.parsing.Parser
import mlogix.compiler.passes.parsing.ParsingPass
import mlogix.compiler.passes.parsing.TokenizationPass
import mlogix.compiler.passes.resolution.ResolutionPass
import mlogix.compiler.passes.resolution.Resolver
import mlogix.compiler.passes.typing.TypeInferencePass
import mlogix.compiler.passes.typing.TypeInferencer
import mlogix.compiler.pipeline.CompilationContext
import mlogix.compiler.pipeline.PhaseTimer
import mlogix.compiler.pipeline.Pipeline

/**
 * 测试统一入口：内存源码 → 词法 / 语法 / 名称解析 / 类型推断。
 *
 * 关键点：这里走的是**真实的 [Pipeline] 与 Pass 包装**，而不是在每个测试类里
 * 手工 new Lexer/Parser/Resolver/TypeInferencer。这样测试同时覆盖：
 * - 各 Pass 的 execute 契约；
 * - Pass 的装配顺序；
 * - 诊断在流水线中的收集方式。
 *
 * 每次调用都会创建新的 [SourceMap] / [DiagHandler] / [CompilationContext]，
 * 因此同一个测试类里连续多次 analyze 不会互相污染。
 */
class CompilerFixture(
    private val config: CompilerConfig = CompilerConfig(),
) {
    var context: CompilationContext = newContext()
        private set

    val diagHandler: DiagHandler
        get() = context.diagHandler

    /** 只跑 TOKENIZATION pass，返回 token 列表。 */
    fun tokenize(source: String): Seq<Token> {
        val prepared = prepare(source)
        val pipeline: Pipeline<SourceFile, Seq<Token>> =
            Pipeline.from(TokenizationPass(Lexer(context)))
        return pipeline.execute(prepared.sourceFile, prepared.sourceFile, PhaseTimer())
    }

    /** 只跑 PARSING pass，返回原始 AST；诊断保留在 [diagHandler]。 */
    fun parse(source: String): Stmt {
        val prepared = prepare(source)
        val pipeline: Pipeline<SourceFile, Stmt> =
            Pipeline.from(ParsingPass(Parser(Lexer(context), context)))
        return pipeline.execute(prepared.sourceFile, prepared.sourceFile, PhaseTimer())
    }

    /** 跑完整的 PARSING → RESOLUTION → TYPE_INFERENCE 流水线。 */
    fun analyze(source: String): AnalysisResult {
        val prepared = prepare(source)
        val pipeline: Pipeline<SourceFile, ResolutionResult> =
            Pipeline.from(ParsingPass(Parser(Lexer(context), context)))
                .then(Pipeline.from(ResolutionPass(Resolver(context))))
                .then(Pipeline.from(TypeInferencePass(TypeInferencer(context))))

        val resolution = pipeline.execute(prepared.sourceFile, prepared.sourceFile, PhaseTimer())
        return AnalysisResult(
            source = source,
            sourceFile = prepared.sourceFile,
            sourceMap = prepared.sourceMap,
            context = context,
            ast = resolution.ast,
            resolution = resolution,
        )
    }

    private fun prepare(source: String): PreparedSource {
        val sourceMap = SourceMap(null)
        val sourceFile = sourceMap.loadSourceMap(source)
        context = CompilationContext(DiagHandler(sourceMap), config)
        return PreparedSource(sourceMap, sourceFile)
    }

    private fun newContext(): CompilationContext =
        CompilationContext(DiagHandler(SourceMap(null)), config)

    private data class PreparedSource(
        val sourceMap: SourceMap,
        val sourceFile: SourceFile,
    )
}
