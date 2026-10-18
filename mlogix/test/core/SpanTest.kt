package mlogix.compiler.core

import mlogix.compiler.core.span.Span
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Span 的位打包与相等语义。
 *
 * 注意：Span.equals 为了 AST 断言方便**故意忽略位置**，真正比较位置要用
 * structuralEquals。这里把两种语义都钉死，避免以后误改。
 */
class SpanTest {

    @Test
    fun `between stores end exclusive length`() {
        val span = Span.between(3, 5, 9)
        assertEquals(3, span.index)
        assertEquals(5, span.start)
        assertEquals(4, span.len)
        assertEquals(9, span.end)
    }

    @Test
    fun `between two spanned values covers both`() {
        val from = Span(0, 2, 3)
        val to = Span(0, 10, 2)
        val span = Span.between(from, to)
        assertEquals(2, span.start)
        assertEquals(12, span.end)
        assertEquals(10, span.len)
    }

    @Test
    fun `between rejects mixed file indexes`() {
        assertThrows(IllegalArgumentException::class.java) {
            Span.between(Span(0, 0, 1), Span(1, 0, 1))
        }
    }

    @Test
    fun `equals ignores position but structuralEquals does not`() {
        val a = Span(0, 1, 2)
        val b = Span(1, 7, 4)
        val sameAsA = Span(0, 1, 2)

        assertTrue(a == b, "equals 故意忽略位置")
        assertEquals(0, a.hashCode())
        assertFalse(a.structuralEquals(b))
        assertTrue(a.structuralEquals(sameAsA))
        assertEquals(a.structuralHashCode(), sameAsA.structuralHashCode())
    }

    @Test
    fun `takeLast keeps the tail or returns self when too short`() {
        val span = Span(0, 10, 5)
        assertTrue(span.takeLast(3).structuralEquals(Span(0, 12, 3)))
        assertTrue(span.takeLast(5).structuralEquals(span))
        assertSame(span, span.takeLast(6))
    }

    @Test
    fun `packed fields survive their maximum values`() {
        val index = (1 shl Span.INDEX_BITS) - 1
        val start = (1 shl Span.START_BITS) - 1
        val len = (1 shl Span.LEN_BITS) - 1
        val span = Span(index, start, len)

        assertEquals(index, span.index)
        assertEquals(start, span.start)
        assertEquals(len, span.len)
    }
}
