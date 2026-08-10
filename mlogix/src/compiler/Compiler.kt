package mlogix.compiler

import arc.files.Fi
import arc.struct.ArrayMap
import arc.struct.ObjectMap
import arc.struct.Seq
import mlogix.compiler.ast.ASTPrinter
import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.SourceMapManager
import mlogix.compiler.core.SourceMapManager.SourceMap
import mlogix.compiler.diagnostic.ProblemCollector
import mlogix.compiler.ir.ResolutionResult
import mlogix.compiler.passes.parsing.Lexer
import mlogix.compiler.passes.parsing.Parser
import mlogix.compiler.passes.parsing.ParsingPass
import mlogix.compiler.passes.resolution.ResolutionPass
import mlogix.compiler.passes.resolution.Resolver
import mlogix.compiler.passes.typing.TypeInferencePass
import mlogix.compiler.passes.typing.TypeInferencer
import mlogix.compiler.pipeline.CompilationContext
import mlogix.compiler.pipeline.CompilationPipeline
import mlogix.util.Log
import java.io.IOException

class Compiler(projectPath: Fi) {
    private val manager: SourceMapManager = SourceMapManager(projectPath)
    private val problems: ProblemCollector = ProblemCollector()
    private val config: CompilerConfig = CompilerConfig()

    fun compile(): Boolean {
        val timer = PhaseTimer()

        // 构建编译管道（词法+语法 -> 名称解析 -> 类型推断；未来在此追加 Desugar / Dataflow / Exhaustiveness 等 Pass）
        val pipeline = CompilationPipeline(
            Seq.with(
                ParsingPass(Parser(Lexer(problems), problems)),
                ResolutionPass(Resolver(problems)),
                TypeInferencePass(TypeInferencer(problems)),
            )
        )

        // 遍历项目树
        try {
            manager.walk { file ->
                if (!file.extension().equals("mlx")) return@walk

                val sourceMap: SourceMap
                try {
                    sourceMap = manager.loadSourceMap(file)
                } catch (e: IOException) {
                    e.printStackTrace()
                    return@walk
                }
                if (sourceMap.source.isEmpty()) return@walk

                // ---------- 编译管道（词法+语法分析 + 名称解析 + 类型推断等 Pass） ----------
                timer.startPhase("编译管道")
                val context = CompilationContext(problems, sourceMap, config)
                val result = pipeline.run(sourceMap, context) as ResolutionResult
                timer.endPhase()

                // ---------- 输出报告 ----------
                problems.printError()
                problems.printWarning()
                if (Log.isAllowed(Log.LogType.DEBUG)) {
                    ASTPrinter.print(result.ast, sourceMap)
                }
            }

        } catch (e: IOException) {
            e.printStackTrace()
        }

        timer.printPhaseTimes()

        if (problems.errorNum() != 0) {
            Log.info(problems.errorNum().toString() + " errors")
        }
        if (problems.warningNum() != 0) {
            Log.info(problems.warningNum().toString() + " warnings")
        }
        if (problems.hasError()) {
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
