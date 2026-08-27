package mlogix.compiler

import arc.files.Fi
import arc.struct.Seq
import mlogix.compiler.ast.ASTPrinter
import mlogix.compiler.core.CompilationMode
import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.SourceMap
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenPrinter
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
import mlogix.util.Log
import java.io.IOException

class Compiler(projectPath: Fi, private val config: CompilerConfig) {
    private val sourceMap: SourceMap = SourceMap(projectPath)
    private val context = CompilationContext(DiagHandler(sourceMap), config)
    private val diagHandler = context.diagHandler

    fun compile(): Boolean {
        val timer = PhaseTimer()
        when (config.mode) {
            CompilationMode.ALL -> {
                val pipeline: Pipeline<SourceFile, ResolutionResult> =
                    Pipeline.from(ParsingPass(Parser(Lexer(context), context)))
                        .then(Pipeline.from(ResolutionPass(Resolver(context))))
                        .then(Pipeline.from(TypeInferencePass(TypeInferencer(context))))

                // 遍历
                walk { sourceFile ->
                    val result = pipeline.execute(sourceFile, sourceFile, timer) // 类型为 ResolutionResult

                    if (Log.isAllowed(Log.LogType.DEBUG)) {
                        ASTPrinter.print(result.ast, sourceFile)
                        println()
                    }
                }
            }

            CompilationMode.TOKENIZATION -> {
                val pipeline: Pipeline<SourceFile, Seq<Token>> =
                    Pipeline.from(TokenizationPass(Lexer(context)))

                // 遍历
                walk { sourceFile ->
                    val tokens = pipeline.execute(sourceFile, sourceFile, timer) // 类型为 Seq<Token>
                    TokenPrinter.print(tokens, sourceFile)
                }
            }
        }

        diagHandler.printError()
        diagHandler.printWarning()

        if (diagHandler.errorNum() != 0) {
            Log.info(diagHandler.errorNum().toString() + " errors")
        }
        if (diagHandler.warningNum() != 0) {
            Log.info(diagHandler.warningNum().toString() + " warnings")
        }
        timer.printPhaseTimes()
        if (diagHandler.hasError()) {
            Log.info("编译失败")
            return false
        } else {
            Log.info("编译成功")
            return true
        }

    }

    /** 遍历项目树 */
    private fun walk(action: (SourceFile) -> Unit) {
        try {
            sourceMap.walk { file ->
                if (!file.extension().equals("mlx")) return@walk

                val sourceFile: SourceFile
                try {
                    sourceFile = sourceMap.loadSourceMap(file)
                } catch (e: IOException) {
                    e.printStackTrace()
                    return@walk
                }
                if (sourceFile.source.isEmpty()) return@walk

                action(sourceFile)
            }

        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
