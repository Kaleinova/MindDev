package mlogix.compiler

import arc.files.Fi
import arc.struct.ArrayMap
import arc.struct.ObjectMap
import arc.struct.Seq
import mlogix.compiler.ast.ASTPrinter
import mlogix.compiler.core.CompilationMode
import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.SourceMap
import mlogix.compiler.core.SourceMap.SourceFile
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
import mlogix.compiler.pipeline.Pipeline
import mlogix.util.Log
import java.io.IOException

class Compiler(projectPath: Fi, private val config: CompilerConfig) {
    private val manager: SourceMap = SourceMap(projectPath)
    private val diagHandler: DiagHandler = DiagHandler(manager)

    fun compile(): Boolean {
        val timer = PhaseTimer()

        // 遍历项目树
        try {
            manager.walk { file ->
                if (!file.extension().equals("mlx")) return@walk

                val sourceFile: SourceFile
                try {
                    sourceFile = manager.loadSourceMap(file)
                } catch (e: IOException) {
                    e.printStackTrace()
                    return@walk
                }
                if (sourceFile.source.isEmpty()) return@walk

                timer.startPhase("编译管道")
                val context = CompilationContext(diagHandler, sourceFile, config)
                when (config.mode) {
                    CompilationMode.ALL -> {
                        val pipeline: Pipeline<SourceFile, ResolutionResult> =
                            Pipeline.from(ParsingPass(Parser(Lexer(diagHandler), diagHandler)))
                                .then(Pipeline.from(ResolutionPass(Resolver(diagHandler))))
                                .then(Pipeline.from(TypeInferencePass(TypeInferencer(diagHandler))))

                        val result = pipeline.execute(sourceFile, context) // 类型为 ResolutionResult
                        if (Log.isAllowed(Log.LogType.DEBUG)) {
                            ASTPrinter.print(result.ast, sourceFile)
                            println()
                        }
                    }

                    CompilationMode.TOKENIZATION -> {
                        val pipeline: Pipeline<SourceFile, Seq<Token>> =
                            Pipeline.from(TokenizationPass(Lexer(diagHandler)))

                        val tokens = pipeline.execute(sourceFile, context) // 类型为 Seq<Token>
                    }
                }
                timer.endPhase()

                diagHandler.printError()
                diagHandler.printWarning()
            }

        } catch (e: IOException) {
            e.printStackTrace()
        }

        timer.printPhaseTimes()

        if (diagHandler.errorNum() != 0) {
            Log.info(diagHandler.errorNum().toString() + " errors")
        }
        if (diagHandler.warningNum() != 0) {
            Log.info(diagHandler.warningNum().toString() + " warnings")
        }
        if (diagHandler.hasError()) {
            Log.info("编译失败")
            return false
        } else {
            Log.info("编译成功")
            return true
        }
    }

    class PhaseTimer {
        private val phaseTimeMap = ArrayMap<String, Long>()
        private var currentPhaseName: String? = null
        private var phaseStart: Long = 0

        fun startPhase(phaseName: String) {
            if (currentPhaseName != null) {
                endPhase()
            }
            currentPhaseName = phaseName
            phaseStart = System.currentTimeMillis()
        }

        fun endPhase() {
            if (currentPhaseName != null) {
                val duration = System.currentTimeMillis() - phaseStart
                phaseTimeMap.put(currentPhaseName, duration)
                currentPhaseName = null
            }
        }

        fun printPhaseTimes() {
            Log.info("=== 编译阶段耗时统计 ===")
            if (Log.isAllowed(Log.LogType.DEBUG)) {
                phaseTimeMap.forEach { entry: ObjectMap.Entry<String, Long> ->
                    System.out.printf("%-10s: %5d ms%n", entry.key, entry.value)
                }
            }

            var total = 0L
            phaseTimeMap.forEach { entry: ObjectMap.Entry<String, Long> ->
                total += entry.value
            }
            System.out.printf("%-10s: %5d ms%n", "总计", total)
        }
    }
}
