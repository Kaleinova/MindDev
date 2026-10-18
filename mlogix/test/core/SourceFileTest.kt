package mlogix.compiler.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SourceFile 的行列号 / 显示宽度基础设施。
 *
 * 这些能力直接决定诊断渲染的位置；此前没有任何单测，属于典型的「编译器地基」。
 */
class SourceFileTest {

    @Test
    fun `LF line offsets are zero based`() {
        val file = SourceFile("ab\ncd\nef")

        assertEquals(0, file.getLine(0))
        assertEquals(0, file.getCol(0))
        assertEquals(1, file.getCol(1))
        assertEquals(1, file.getLine(3))
        assertEquals(0, file.getCol(3))
        assertEquals(2, file.getLine(6))
    }

    @Test
    fun `CRLF and CR are treated as one line break`() {
        val crlf = SourceFile("ab\r\ncd")
        assertEquals(1, crlf.getLine(4))
        assertEquals(0, crlf.getCol(4))
        assertEquals("ab", crlf.getLineString(0))
        assertEquals("ab\r\n", crlf.getLineStringWithNewline(0))

        val cr = SourceFile("ab\rcd")
        assertEquals(1, cr.getLine(3))
        assertEquals(0, cr.getCol(3))
        assertEquals("ab", cr.getLineString(0))
        assertEquals("ab\r", cr.getLineStringWithNewline(0))
    }

    @Test
    fun `line strings drop only line endings`() {
        val file = SourceFile("first\nsecond")
        assertEquals("first", file.getLineString(0))
        assertEquals("second", file.getLineString(1))
        assertEquals("first\n", file.getLineStringWithNewline(0))
        assertEquals("second", file.getLineStringWithNewline(1))
    }

    @Test
    fun `display column counts CJK as two and zero width as zero`() {
        val file = SourceFile("a中b")
        assertEquals(1, file.getDisplayCol(1), "汉字前是 1 列")
        assertEquals(3, file.getDisplayCol(2), "汉字占 2 列")

        val zeroWidth = SourceFile("a\u200Bb")
        assertEquals(1, zeroWidth.getDisplayCol(2), "零宽字符不占列")
    }

    @Test
    fun `empty source has a single empty line`() {
        val file = SourceFile("")
        assertEquals(0, file.length())
        assertEquals(0, file.getLine(0))
        assertEquals("", file.getLineString(0))
    }

    @Test
    fun `trailing newline creates an addressable empty last line`() {
        val file = SourceFile("a\n")
        assertEquals(2, file.length())
        assertEquals(1, file.getLine(2))
        assertEquals("", file.getLineString(1))
    }
}
