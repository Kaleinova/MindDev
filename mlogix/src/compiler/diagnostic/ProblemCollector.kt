package mlogix.compiler.diagnostic

import arc.struct.Seq
import arc.util.Log

class ProblemCollector {
    val errors = Seq<Problem>()
    val warnings = Seq<Problem>()

    fun createSnapshot(): ProblemCollectorSnapshot {
        return ProblemCollectorSnapshot(errorNum(), warningNum())
    }

    fun restoreSnapshot(snapshot: ProblemCollectorSnapshot) {
        // Left closed and right closed
        if (snapshot.errorNum != errorNum()) {
            errors.removeRange(snapshot.errorNum, errorNum() - 1)
        }
        if (snapshot.warningNum != warningNum()) {
            warnings.removeRange(snapshot.warningNum, warningNum() - 1)
        }
    }

    fun hasError(): Boolean {
        return !errors.isEmpty
    }

    fun errorNum(): Int {
        return errors.size
    }

    fun warningNum(): Int {
        return warnings.size
    }

    fun addError(error: Problem) {
        errors.add(error)
    }

    fun addWarning(warning: Problem) {
        warnings.add(warning)
    }

    fun printError() {
        errors.forEach { e: Problem -> Log.err(e.toString()) }
    }

    fun printWarning() {
        warnings.forEach { w: Problem -> Log.warn(w.toString()) }
    }

    fun clear() {
        errors.clear()
        warnings.clear()
    }

    data class ProblemCollectorSnapshot(val errorNum: Int, val warningNum: Int)
}
