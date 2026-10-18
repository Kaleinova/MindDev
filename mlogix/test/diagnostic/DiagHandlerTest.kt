package mlogix.compiler.diagnostic

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * DiagHandler 的计数、清空与快照回滚。
 *
 * restoreSnapshot 供「回溯解析/尝试性解析」使用；它按左闭右闭区间删除，
 * 这里把边界固定下来，避免以后改成左闭右开时静默漏删/多删。
 */
class DiagHandlerTest {

    private fun error(message: String): Diagnostic =
        Diagnostic.SemanticDiag(message, Diagnostic.DiagLevel.ERROR)

    private fun warning(message: String): Diagnostic =
        Diagnostic.SemanticDiag(message, Diagnostic.DiagLevel.WARNING)

    @Test
    fun `counts and hasError reflect added diagnostics`() {
        val handler = DiagHandler()
        assertFalse(handler.hasError())

        handler.addError(error("e1"))
        handler.addWarning(warning("w1"))

        assertEquals(1, handler.errorNum())
        assertEquals(1, handler.warningNum())
        assertTrue(handler.hasError())
    }

    @Test
    fun `clear removes both errors and warnings`() {
        val handler = DiagHandler()
        handler.addError(error("e1"))
        handler.addWarning(warning("w1"))

        handler.clear()

        assertEquals(0, handler.errorNum())
        assertEquals(0, handler.warningNum())
        assertFalse(handler.hasError())
    }

    @Test
    fun `restoreSnapshot drops diagnostics added after the snapshot`() {
        val handler = DiagHandler()
        handler.addError(error("e1"))
        handler.addError(error("e2"))
        handler.addWarning(warning("w1"))
        val snapshot = handler.createSnapshot()

        handler.addError(error("e3"))
        handler.addWarning(warning("w2"))
        handler.restoreSnapshot(snapshot)

        assertEquals(2, handler.errorNum())
        assertEquals(1, handler.warningNum())
        assertEquals("e1", handler.errors[0].message)
        assertEquals("e2", handler.errors[1].message)
    }

    @Test
    fun `restoreSnapshot is a no-op when nothing was added`() {
        val handler = DiagHandler()
        handler.addError(error("e1"))
        val snapshot = handler.createSnapshot()

        handler.restoreSnapshot(snapshot)

        assertEquals(1, handler.errorNum())
        assertEquals("e1", handler.errors[0].message)
    }
}
