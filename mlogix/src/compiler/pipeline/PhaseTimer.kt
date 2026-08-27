package mlogix.compiler.pipeline

import arc.struct.ArrayMap
import arc.struct.ObjectMap
import mlogix.compiler.core.pass.PassId
import mlogix.util.I18N.bundle
import mlogix.util.Log
import kotlin.math.max

class PhaseTimer {
    private val phaseTimeMap = ArrayMap<PassId, Long>(PassId.entries.size)
    private var currentPhaseId: PassId? = null
    private var phaseStart: Long = 0

    fun startPhase(id: PassId) {
        if (currentPhaseId != null) {
            endPhase()
        }
        currentPhaseId = id
        phaseStart = System.currentTimeMillis()
    }

    fun endPhase() {
        if (currentPhaseId != null) {
            val duration = System.currentTimeMillis() - phaseStart
            val origin = phaseTimeMap.get(currentPhaseId)
            if (origin == null) {
                phaseTimeMap.put(currentPhaseId, duration)
            } else {
                phaseTimeMap.put(currentPhaseId, origin + duration)
            }
            currentPhaseId = null
        }
    }

    fun printPhaseTimes() {
        var total = 0L
        if (Log.isAllowed(Log.LogType.DEBUG)) {
            Log.info("=== ${bundle.get("compiler.time_consuming_details")} ===")

            var maxKeyStrLen = 5
            phaseTimeMap.forEach { entry: ObjectMap.Entry<PassId, Long> ->
                total += entry.value
                maxKeyStrLen = max(maxKeyStrLen, entry.key.name.lowercase().length)
            }
            val maxValueStrLen = total.toString().length
            phaseTimeMap.forEach { entry: ObjectMap.Entry<PassId, Long> ->
                System.out.printf(
                    "%-${maxKeyStrLen}s : %,${maxValueStrLen}d ms%n",
                    entry.key.name.lowercase(),
                    entry.value
                )
            }

            System.out.printf("%-${maxKeyStrLen}s : %,${maxValueStrLen}d ms%n", "total", total)
        } else {
            phaseTimeMap.forEach { entry: ObjectMap.Entry<PassId, Long> ->
                total += entry.value
            }
            Log.info("${bundle.get("compiler.time_consuming")} : ${"%,d".format(total)} ms")
        }
    }
}