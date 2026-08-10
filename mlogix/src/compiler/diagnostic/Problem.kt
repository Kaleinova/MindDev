package mlogix.compiler.diagnostic

import arc.struct.Seq
import mlogix.compiler.core.SourceMapManager.SourceMap
import mlogix.compiler.core.span.Spanned
import mlogix.util.Ansi
import kotlin.math.max

// 用于表示编译器问题，包含错误和警告
abstract class Problem(
    val sourceMap: SourceMap,   // 这个问题所在文件
    val problemName: String,    // 这个问题的名称
    val level: ProblemLevel     // 问题级别（错误或警告）
) {
    val lineInfos = Seq<LineInfo>(2)
    var centerLine = null as LineInfo?

    fun point(obj: Spanned, text: String): Problem {
        val span = obj.span()
        return point(span.start, span.end, text)
    }

    fun point(start: Int, end: Int, text: String): Problem {
        val line = sourceMap.getLine(start)
        val lineInfo = getLineInfo(line)
        if (centerLine == null) centerLine = lineInfo
        val col = sourceMap.getCol(start)

        lineInfo.point(
            col,
            "^".repeat(max(1, end - start)),
            text
        )
        return this
    }

    fun info(obj: Spanned, text: String): Problem {
        val span = obj.span()
        return info(span.start, span.end, text)
    }

    fun info(start: Int, end: Int, text: String): Problem {
        val lineInfo = getLineInfo(sourceMap.getLine(start))
        lineInfo.info(
            sourceMap.getCol(start),
            "-".repeat(max(1, end - start)),
            text
        )
        return this
    }

    // 获取行，若不存在则新建并返回
    private fun getLineInfo(line: Int): LineInfo {
        for (lineInfo in lineInfos) {
            if (lineInfo.line == line) return lineInfo
        }
        val lineInfo = LineInfo(line, sourceMap.getLineString(line))
        lineInfos.add(lineInfo)
        return lineInfo
    }

    override fun toString(): String {
        val color = if (level == ProblemLevel.ERROR) Ansi.RED else Ansi.YELLOW
        val str = StringBuilder("$color${level.name.lowercase()}: $problemName${Ansi.DEFAULT}\n")
        lineInfos.sort(Comparator.comparing { li -> li.line })

        var maxLineDigitLen = 0  // 所有 LineInfo 中最长的行号长度
        for (lineInfo in lineInfos) {
            maxLineDigitLen = maxOf(maxLineDigitLen, lineInfo.line.toString().length)
        }

        //  --> Path:line:col
        str.append(" ".repeat(maxLineDigitLen - 1))
            .append(" --> ")
            .append(sourceMap.relativePath)
            .append(":").append(centerLine?.line ?: lineInfos[0].line)
            .append(":").append(centerLine?.col ?: lineInfos[0].col)
            .append("\n")

        for (lineInfo in lineInfos) {
            str.append(lineInfo.format(maxLineDigitLen))
        }
        return str.toString()
    }

    enum class ProblemLevel {
        WARNING, ERROR
    }

    /* Lexer产生的问题 */
    class LexerProblem(sourceMap: SourceMap, problemName: String, level: ProblemLevel) :
        Problem(sourceMap, problemName, level)

    /* Parser产生的问题 */
    class ParserProblem(sourceMap: SourceMap, problemName: String, level: ProblemLevel) :
        Problem(sourceMap, problemName, level)

    /* SemanticAnalyzer产生的问题 */
    class SemanticProblem(sourceMap: SourceMap, problemName: String, level: ProblemLevel) :
        Problem(sourceMap, problemName, level)

    /**
     * @param col 从1开始
     */
    data class Info(val col: Int, val indicator: String, val text: String)

    // 储存一行的错误信息
    class LineInfo(
        val line: Int,
        val lineString: String
    ) {
        val infos = Seq<Info>(2)
        var col: Int = 0

        fun point(startCol: Int, indicator: String, text: String) {
            this.col = startCol
            infos.add(Info(startCol, indicator, text))
        }

        fun info(startCol: Int, indicator: String, text: String) {
            infos.add(Info(startCol, indicator, text))
        }

        /**
         * @param maxLineDigitLen 所有 LineInfo 中最长的行号长度
         */
        fun format(maxLineDigitLen: Int): String {
            val str = StringBuilder()

            //   ┃
            str.append(Ansi.CYAN).append(" ".repeat(maxLineDigitLen)).append(" ┃ ").append(Ansi.DEFAULT).append("\n")

            // L ┃ lineString
            val lineDigitLen = line.toString().length
            str.append(Ansi.CYAN)
                .append(" ".repeat(maxLineDigitLen - lineDigitLen)).append(line).append(" ┃ ")
                .append(Ansi.DEFAULT).append(lineString).append("\n")

            if (infos.isEmpty) return str.toString()  // 为空退出防止越界

            // 先保证后加入的靠下，后保证顺序
            infos.sortComparing { info -> info.col }

            //   ┃  ^ - ^ infos[last]text
            str.append(Ansi.CYAN).append(" ".repeat(maxLineDigitLen)).append(" ┃ ").append(Ansi.DEFAULT)
            var curCol = 1  // 标识当前输出列
            for ((col, indicator) in infos) {
                val count = col - curCol
                if (count < 0) continue  // 与上一个重叠
                str.append(" ".repeat(count))
                str.append(indicator)
                curCol = col + indicator.length
            }
            // 先保证后加入的靠下，后保证顺序
            infos.reverse().sortComparing { info -> info.col }
            str.append(" ").append(infos.last().text)
            str.append("\n")

            //   ┃  | |
            //   ┃  | infos[last-1].text
            //   ┃  |
            //   ┃  infos[last-2].text
            for (i in infos.size - 1 downTo 1) {
                val text = infos[i - 1].text
                if (text.isEmpty()) continue

                //   ┃  | |
                str.append(Ansi.CYAN).append(" ".repeat(maxLineDigitLen)).append(" ┃ ").append(Ansi.DEFAULT)
                curCol = 1
                for (j in 0 until i) {
                    val count = infos[j].col - curCol
                    if (count < 0) continue  // 重叠
                    str.append(" ".repeat(count))
                    str.append("|")
                    curCol = infos[j].col + 1
                }
                str.append("\n")

                //  ┃ | infos[last-1].text
                str.append(Ansi.CYAN).append(" ".repeat(maxLineDigitLen)).append(" ┃ ").append(Ansi.DEFAULT)
                curCol = 1
                for (j in 0 until i - 1) {
                    if (infos[j].col == infos[j + 1].col) {  // 与后一个重叠
                        continue  // 跳过此次
                    }
                    val count = infos[j].col - curCol
                    str.append(" ".repeat(count))
                    str.append("|")
                    curCol = infos[j].col + 1
                }
                str.append(" ".repeat(maxOf(0, infos[i - 1].col - curCol)))
                str.append(text)
                str.append("\n")
            }

            return str.toString()
        }
    }
}