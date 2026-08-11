package mlogix.compiler.core

import arc.files.Fi
import arc.func.Cons
import arc.struct.IntSeq
import arc.struct.ObjectMap
import arc.struct.Seq
import java.io.IOException

/**
 * 需要在项目中管理时使用SourceMapManager，否则可以直接使用SourceMap
 */
class SourceMapManager(/* 项目根目录 */val projectPath: Fi) {
    private val sourceMaps = ObjectMap<Fi, SourceMap>()

    /* 以此通过索引获取sourceMap */
    private val sourceMapList = Seq<SourceMap>()

    /**
     * 加载文件并创建 SourceMap
     */
    @Throws(IOException::class)
    fun loadSourceMap(filePath: Fi): SourceMap {
        val sourceMap = SourceMap(filePath, sourceMapList.size, projectPath)
        sourceMaps.put(filePath, sourceMap)
        sourceMapList.add(sourceMap)
        return sourceMap
    }

    /**
     * 获取文件的 SourceMap
     */
    fun getSourceMap(filePath: Fi): SourceMap? {
        return sourceMaps[filePath]
    }

    /**
     * 通过索引获取sourceMap
     */
    fun getSourceMap(index: Int): SourceMap? {
        return sourceMapList.get(index)
    }

    @Throws(IOException::class)
    fun walk(cons: Cons<Fi>) {
        projectPath.findAll { f -> f.extension().equals("mlx") }.forEach { f -> cons.get(f) }
    }

    class SourceMap {
        val filePath: Fi?
        val relativePath: String /* 相对于项目根目录的相对目录 */
        val source: String /* 存储所有字符 */
        val index: Int /* 在SourceMapManager中的索引 */
        private val lineOffsetList: IntSeq /* 每行的起始字符索引 */

        constructor(filePath: Fi, index: Int, projectPath: Fi) {
            this.filePath = filePath
            this.relativePath = projectPath.file().toURI().relativize(filePath.file().toURI()).path
            this.source = loadSource(filePath)
            this.lineOffsetList = buildLineOffsetList()
            this.index = index
        }

        constructor(source: String) {
            this.filePath = null
            this.relativePath = "src"
            this.source = loadSource(source)
            this.lineOffsetList = buildLineOffsetList()
            this.index = 0
        }

        /**
         * 加载文件内容为字符列表（自动处理UTF-8）
         */
        @Throws(IOException::class)
        private fun loadSource(filePath: Fi): String {
            return loadSource(filePath.readString()) // Java 11+ 直接读取为UTF-8字符串
        }

        /**
         * 从字符串加载字符列表
         */
        private fun loadSource(source: String): String {
            return source.replace("\r\n", "\n").replace('\r', '\n')
        }

        /**
         * 构建行号表（记录每行的起始字符索引）
         *
         * - `ab\ncd\nef` -> `{0, 3, 6}`
         * - `ab\r\ncd\r\nef` -> `{0, 4, 8}`
         * - `ab\rcd\ref` -> `{0, 3, 6}`
         */
        private fun buildLineOffsetList(): IntSeq {
            val offsetList = IntSeq()
            offsetList.add(0) // 第一行从索引0开始

            var i = 0
            while (i < length()) {
                when (charAt(i)) {
                    '\n' -> { // LF (Unix)
                        i++
                        if (i < length()) offsetList.add(i)
                    }

                    '\r' -> { // CR (Mac) 或 CRLF (Windows)
                        i++
                        if (i < length()) {
                            if (charAt(i) == '\n') {
                                i++
                            }
                            offsetList.add(i)
                        }
                    }

                    else -> i++
                }
            }
            return offsetList
        }

        /**
         * 根据字符索引获取行号（从0开始）
         * 使用二分查找
         */
        fun getLine(charIndex: Int): Int {
            var low = 0
            var high = lineOffsetList.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (lineOffsetList[mid] <= charIndex) {
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return low - 1
        }

        /**
         * 根据字符索引获取列号(从0开始)
         */
        fun getCol(charIndex: Int): Int {
            return charIndex - lineOffsetList[getLine(charIndex)]
        }

        /**
         * 根据字符索引和行号(从0开始)获取列号(从0开始)
         */
        fun getColWithLine(charIndex: Int, line: Int): Int {
            return charIndex - lineOffsetList[line]
        }

        /**
         * 根据字符索引获取字符在终端显示上的列号(从0开始)
         * 部分符号为0宽，英文数字符号宽为1，汉字等全角字符宽为2
         */
        fun getDisplayCol(charIndex: Int): Int {
            val line = getLine(charIndex)
            val lineStart = lineOffsetList[line - 1]
            var displayCol = 0
            var i = lineStart
            while (i < charIndex) {
                val codePoint = Character.codePointAt(source, i)
                displayCol += charDisplayWidth(codePoint)
                i += Character.charCount(codePoint)
            }
            return displayCol
        }

        /**
         * 获取单个字符在终端上的显示宽度：0（零宽/组合符号）、1（英文数字符号）、2（汉字等全角字符）
         */
        private fun charDisplayWidth(codePoint: Int): Int {
            // 零宽字符：零宽空格/连接符/不换行空格、组合用附加符号、变体选择符等
            when (codePoint) {
                0x200B, 0x200C, 0x200D, 0xFEFF -> return 0
                in 0x0300..0x036F, in 0x1AB0..0x1AFF, in 0x1DC0..0x1DFF -> return 0
                in 0x20D0..0x20FF, in 0xFE20..0xFE2F -> return 0

                // 宽为2的全角字符（汉字、假名、谚文、全角标点符号等）
                in 0x1100..0x115F -> return 2 // 谚文Jamo
                in 0x2E80..0x303E -> return 2 // CJK部首..CJK标点
                in 0x3041..0x33FF -> return 2 // 平假名..CJK兼容字符
                in 0x3400..0x4DBF -> return 2 // CJK扩展A
                in 0x4E00..0x9FFF -> return 2 // CJK统一汉字
                in 0xA000..0xA4CF -> return 2 // 彝文..谚文字母
                in 0xAC00..0xD7A3 -> return 2 // 谚文音节
                in 0xF900..0xFAFF -> return 2 // CJK兼容表意文字
                in 0xFE30..0xFE4F -> return 2 // CJK兼容形式
                in 0xFF00..0xFF60 -> return 2 // 全角ASCII变体
                in 0xFFE0..0xFFE6 -> return 2 // 全角货币符号
                in 0x20000..0x3FFFD -> return 2 // CJK扩展B及以上
                else -> return 1
            }
        }

        /** 截取为字符串 */
        fun subString(start: Int, end: Int): String {
            return source.substring(start, end)
        }

        /**
         * 跟据行号(从0开始)获取一行字符串，不带换行符
         */
        fun getLineString(line: Int): String {
            // 最后一行
            if (line == lineOffsetList.size - 1) {
                return subString(lineOffsetList[line], source.length)
            }
            return subString(lineOffsetList[line], lineOffsetList[line + 1] - 1)
                .dropLastWhile { it == '\n' || it == '\r' }
        }

        fun length(): Int {
            return source.length
        }

        fun charAt(index: Int): Char {
            return source[index]
        }
    }
}