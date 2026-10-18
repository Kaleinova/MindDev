package mlogix.compiler.pipeline

import arc.struct.Seq
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.pass.CompilerPass
import mlogix.compiler.core.pass.PassId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pipeline 的组合语义：then 必须按顺序执行，from 必须把 pass 的 id 交给计时器。
 *
 * 这是 Pass 装配层的基础设施；此前完全没有单测，一旦 then 的实现顺序写反，
 * 端到端测试也可能因为副作用而「看起来正常」。
 */
class PipelineTest {

    private class RecordingPass(
        override val id: PassId,
        private val order: Seq<String>,
        private val label: String,
        private val transform: (Int) -> Int = { it },
    ) : CompilerPass<Int, Int> {
        override val dependencies: Set<PassId> = emptySet()

        override fun execute(input: Int, sourceFile: SourceFile): Int {
            order.add(label)
            return transform(input)
        }
    }

    @Test
    fun `then runs the current pipeline before the next one`() {
        val order = Seq<String>()
        val pipeline = Pipeline.from(RecordingPass(PassId.PARSING, order, "first") { it + 1 })
            .then(Pipeline.from(RecordingPass(PassId.RESOLUTION, order, "second") { it * 2 }))

        val result = pipeline.execute(1, SourceFile(""), PhaseTimer())

        assertEquals(4, result)
        assertEquals(Seq.with("first", "second"), order)
    }

    @Test
    fun `lambda pipeline receives the original input and source file`() {
        val sourceFile = SourceFile("abc")
        val pipeline: Pipeline<Int, String> = Pipeline { input, file, timer ->
            input.toString() + ":" + file.length()
        }

        assertEquals("7:3", pipeline.execute(7, sourceFile, PhaseTimer()))
    }

    @Test
    fun `from passes through the value produced by the pass`() {
        val order = Seq<String>()
        val pipeline = Pipeline.from(RecordingPass(PassId.TYPE_INFERENCE, order, "typed") { it + 10 })

        assertEquals(11, pipeline.execute(1, SourceFile(""), PhaseTimer()))
        assertEquals(Seq.with("typed"), order)
    }
}
