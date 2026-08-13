package mlogix.compiler.diagnostic

import arc.struct.Seq
import arc.util.Log
import mlogix.compiler.core.SourceMap

/**
 * 诊断收集器：贯穿编译管道，集中管理所有 [Diagnostic]。
 *
 * 持有 [SourceMap]，打印诊断时把 span 的文件索引解析为源码文件（对齐 rustc 的 Handler + SourceMap）。
 */
class DiagHandler(
    /** 用于把 span 的文件索引解析为源码；单测等不打印场景可传 null */
    val sourceMap: SourceMap? = null,
) {
    val errors = Seq<Diagnostic>()
    val warnings = Seq<Diagnostic>()

    fun createSnapshot(): DiagCollectorSnapshot {
        return DiagCollectorSnapshot(errorNum(), warningNum())
    }

    fun restoreSnapshot(snapshot: DiagCollectorSnapshot) {
        // 左闭右闭
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
        errors.forEach { e: Diagnostic -> Log.err(e.render(sourceMap)) }
    }

    fun printWarning() {
        warnings.forEach { w: Diagnostic -> Log.warn(w.render(sourceMap)) }
    }

    fun clear() {
        errors.clear()
        warnings.clear()
    }

    data class DiagCollectorSnapshot(val errorNum: Int, val warningNum: Int)
}
