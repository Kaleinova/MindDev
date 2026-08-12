package mlogix.compiler.diagnostic

import arc.struct.Seq
import arc.util.Log

class DiagCollector {
    val errors = Seq<Diagnostic>()
    val warnings = Seq<Diagnostic>()

    fun createSnapshot(): DiagCollectorSnapshot {
        return DiagCollectorSnapshot(errorNum(), warningNum())
    }

    fun restoreSnapshot(snapshot: DiagCollectorSnapshot) {
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

    fun addError(error: Diagnostic) {
        errors.add(error)
    }

    fun addWarning(warning: Diagnostic) {
        warnings.add(warning)
    }

    fun printError() {
        errors.forEach { e: Diagnostic -> Log.err(e.toString()) }
    }

    fun printWarning() {
        warnings.forEach { w: Diagnostic -> Log.warn(w.toString()) }
    }

    fun clear() {
        errors.clear()
        warnings.clear()
    }

    data class DiagCollectorSnapshot(val errorNum: Int, val warningNum: Int)
}
