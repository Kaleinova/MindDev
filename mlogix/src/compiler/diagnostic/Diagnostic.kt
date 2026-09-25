package mlogix.compiler.diagnostic

import arc.struct.IntIntMap
import arc.struct.IntSeq
import arc.struct.Seq
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.SourceMap
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.span.Spanned
import mlogix.util.Ansi
import kotlin.math.max
import kotlin.math.min

/**
 * 编译器诊断（错误 / 警告）。
 *
 * 设计对齐 rustc：
 * - 不持有 [SourceFile]——文件信息由每个 [Label.span] 中的文件索引提供，
 *   渲染时经 [SourceMap] 解析出对应 [SourceFile]；
 * - [label] 标签（渲染为 `^`/`-`），
 *   [note] 提示（以 `note:` 渲染，样式类似 label）。
 */
abstract class Diagnostic(
    val message: String,   // 问题描述
    val level: DiagLevel,  // 问题级别
) {
    /** 标签列表：第 1 个为主标签（`^`），其余为次级标签（`-`） */
    val labels = Seq<Label>(2)

    /** 建议列表 */
    val suggestions = Seq<Suggestion>(1)

    fun label(spanned: Spanned, text: String = ""): Diagnostic {
        labels.add(Label(spanned.span(), text, if (labels.isEmpty) LabelStyle.Primary else LabelStyle.Secondary))
        return this
    }

    /** 添加一条提示 */
    fun note(text: String): Note {
        val note = Note(text)
        suggestions.add(note)
        return note
    }

    /** 添加一条帮助 */
    fun help(text: String): Help {
        val help = Help(text)
        suggestions.add(help)
        return help
    }

    enum class DiagLevel {
        WARNING, ERROR
    }

    /** 标签样式 */
    enum class LabelStyle(val marker: Char) {
        Primary('^'),
        Secondary('-'),
        Insert('+'),
        Delete('-'),
        Replace('~'),
    }

    /** 一个带位置与样式的标签 */
    data class Label(val span: Span, val text: String, val style: LabelStyle)

    /** 一条建议 */
    abstract class Suggestion(val text: String) {
        /** 标签列表：第 1 个为主标签（`^`），其余为次级标签（`-`） */
        val labels = Seq<Label>(1)
    }

    /** 一条提示 */
    class Note(text: String) : Suggestion(text) {
        fun label(spanned: Spanned, text: String = "") {
            labels.add(Label(spanned.span(), text, if (labels.isEmpty) LabelStyle.Primary else LabelStyle.Secondary))
        }
    }

    /** 一条帮助 */
    class Help(text: String) : Suggestion(text) {
        fun insert(spanned: Spanned, code: String) {
            labels.add(Label(spanned.span(), code, LabelStyle.Insert))
        }

        fun delete(spanned: Spanned) {
            labels.add(Label(spanned.span(), "", LabelStyle.Delete))
        }

        fun replace(spanned: Spanned, code: String) {
            labels.add(Label(spanned.span(), code, LabelStyle.Replace))
        }
    }

    /** Lexer 产生的问题 */
    class LexerDiag(message: String, level: DiagLevel) : Diagnostic(message, level)

    /** Parser 产生的问题 */
    class ParserDiag(message: String, level: DiagLevel) : Diagnostic(message, level)

    /** SemanticAnalyzer 产生的问题 */
    class SemanticDiag(message: String, level: DiagLevel) : Diagnostic(message, level)

    // ---------- 渲染实现 ----------
    /**
     * 渲染诊断（含代码片段）。
     *
     * @param sourceMap 用于把 span 的文件索引解析为源码；为 null（如单测）或无标签时只输出标题行。
     */
    fun render(sourceMap: SourceMap?): String {
        return buildString {
            val levelColor = if (level == DiagLevel.ERROR) Ansi.RED else Ansi.YELLOW
            // error/warning: ......
            append("$levelColor${level.name.lowercase()}: $message${Ansi.DEFAULT}\n")
            if (sourceMap == null) return toString()
            val maxLineStrLen = maxLineStrLen(sourceMap, labels, suggestions)
            var primary: Label? = null
            var primaryFile: SourceFile? = null

            if (!labels.isEmpty) {// 按文件分组（主标签所在文件在最前）
                val groups = groupByFile(labels)
                primary = labels[0]
                primaryFile = sourceMap.getSourceFile(primary.span.index())
                if (primaryFile != null) {
                    append(renderSnippet(primaryFile, groups[0], "-->", maxLineStrLen))
                }

                for (secondary in groups.iterator().also { it.next() }) {
                    val secondaryFile = sourceMap.getSourceFile(secondary.first().span.index()) ?: continue
                    append(renderSnippet(secondaryFile, secondary, ":::", maxLineStrLen))
                }
            }

            for (suggestion in suggestions) {
                append(
                    renderSuggestion(
                        sourceMap,
                        suggestion,
                        primaryFile?.getLine(primary!!.span.start()) ?: -1,
                        maxLineStrLen,
                    )
                )
            }
        }
    }


    override fun toString(): String = render(null)


    /**
     * 渲染一个文件
     */
    private fun renderSnippet(file: SourceFile, labels: Seq<Label>, fileMark: String, maxLineStrLen: Int): String {
        return buildString {
            val primary = labels[0]
            val lineNumStr = (file.getLine(primary.span.start()) + 1).toString()
            val colStr = (file.getCol(primary.span.start()) + 1).toString()
            append(" ".repeat(maxLineStrLen))
            append("$fileMark ${file.relativePath}:$lineNumStr:$colStr\n")

            labels.sort(Comparator { a, b ->
                val lineDiff = file.getLine(a.span.start()) - file.getLine(b.span.start())
                if (lineDiff != 0) lineDiff
                else file.getCol(a.span.start()) - file.getCol(b.span.start())
            })

            append("${renderBlank(maxLineStrLen)}\n")

            var index = 0
            var lastLine = -1
            while (index < labels.size) {
                val line = file.getLine(labels.get(index).span.start())
                val lineLabels = Seq<Label>(2)
                while (index < labels.size && file.getLine(labels.get(index).span.start()) == line) {
                    lineLabels.add(labels.get(index))
                    index++
                }
                if (lastLine >= 0) {
                    when (line - lastLine) {
                        0, 1 -> {}

                        2 -> append(renderLine(file, lastLine + 1, maxLineStrLen))
                        else -> append("...\n")
                    }
                }
                append(renderLine(file, line, lineLabels, maxLineStrLen))
                lastLine = line
            }
            append("${renderBlank(maxLineStrLen)}\n")
        }
    }


    private fun renderBlank(maxLineStrLen: Int): String {
        return "${Ansi.CYAN}${" ".repeat(maxLineStrLen)} | ${Ansi.DEFAULT}"
    }

    private fun renderLine(file: SourceFile, line: Int, maxLineStrLen: Int): String {
        val lineNumStr = (line + 1).toString()
        return buildString {
            // 源码行
            append(
                "${Ansi.CYAN}${" ".repeat(maxLineStrLen - lineNumStr.length)}$lineNumStr | ${Ansi.DEFAULT}${
                    file.getLineString(line)
                }\n"
            )
        }
    }

    /**
     * 渲染一行源码上的全部标注。
     *
     * 同一行上**重叠/嵌套**的 label（例如主 label 圈住整个类型表达式 `Option<Int, Str>`，
     * 次级 label 只圈其中一个实参 `Str`）会按「标注行」打包：放不进当前标注行的 label
     * 进入下一标注行，**绝不丢弃**（rustc 同样把重叠标注分行渲染）。
     * 同一标注行内保证各 label 从左到右互不重叠，因此可以直接顺序绘制标记。
     */
    private fun renderLine(file: SourceFile, line: Int, originalLabels: Seq<Label>, maxLineStrLen: Int): String {
        val lineNumStr = (line + 1).toString()

        return buildString {
            // 源码行
            append(
                "${Ansi.CYAN}${" ".repeat(maxLineStrLen - lineNumStr.length)}$lineNumStr | ${Ansi.DEFAULT}${
                    file.getLineString(line)
                }\n"
            )

            // 准备数据：每个 label 的起始列与标记长度
            val cols = IntSeq(originalLabels.size)
            val lens = IntSeq(originalLabels.size)
            for (label in originalLabels) {
                cols.add(file.getDisplayCol(label.span.start()))
                lens.add(markLen(file, label.span))
            }

            // 逐标注行渲染（大多数情况下只有一个标注行，与旧输出一致）
            for (row in packLabelRows(originalLabels, cols, lens)) {
                renderLabelRow(originalLabels, row, cols, lens, maxLineStrLen)
            }
        }
    }

    /**
     * 把一行上的 label 打包成若干「标注行」：按给定顺序（调用方已按列排序）贪心放入第一个
     * 放得下的标注行，与同行已有 label 重叠时就新开一行。
     *
     * @return 每个标注行内的 label 下标（相对 [labels]）
     */
    private fun packLabelRows(labels: Seq<Label>, cols: IntSeq, lens: IntSeq): Seq<IntSeq> {
        val rows = Seq<IntSeq>(2)
        val rowEnds = IntSeq(2)
        for (i in 0 until labels.size) {
            val col = cols[i]
            var placed = false
            for (r in 0 until rows.size) {
                if (col < rowEnds[r]) continue
                rows[r].add(i)
                rowEnds[r] = col + lens[i]
                placed = true
                break
            }
            if (!placed) {
                val row = IntSeq(2)
                row.add(i)
                rows.add(row)
                rowEnds.add(col + lens[i])
            }
        }
        return rows
    }

    /**
     * 渲染一个标注行：先画该行各 label 的标记，再补文本——
     * 行内最后一个 label 的文本跟在标记行之后，其余文本用竖线连接依次向下（既有样式）。
     */
    private fun StringBuilder.renderLabelRow(
        labels: Seq<Label>,
        row: IntSeq,
        cols: IntSeq,
        lens: IntSeq,
        maxLineStrLen: Int,
    ) {
        // ┃  ^ - ^ labels[last]text
        val spaces = IntSeq(row.size)
        var curCol = 0
        append(renderBlank(maxLineStrLen))
        for (k in 0 until row.size) {
            val index = row[k]
            val space = cols[index] - curCol
            spaces.add(space)
            append(" ".repeat(space))
            append(labels[index].style.marker.toString().repeat(lens[index]))
            curCol = cols[index] + lens[index]
        }
        append(" ${labels[row[row.size - 1]].text}\n")

        // ┃  | |
        // ┃  | labels[k-1].text
        // ┃  |
        // ┃  labels[0].text
        for (k in row.size - 2 downTo 0) {
            val text = labels[row[k]].text
            if (text.isEmpty()) continue

            // ┃  | |
            append(renderBlank(maxLineStrLen))
            for (j in 0..k) {
                append(" ".repeat(spaces[j]))
                append("|")
            }
            append("\n")

            // ┃ | labels[k].text
            append(renderBlank(maxLineStrLen))
            for (j in 0..k - 1) {
                append(" ".repeat(spaces[j]))
                append("|")
            }
            append(" ".repeat(spaces[k]))
            append(text)
            append("\n")
        }
    }

    private fun renderSuggestion(
        map: SourceMap,
        suggestion: Suggestion,
        primaryLine: Int,
        maxLineStrLen: Int
    ): String {
        val identify = when (suggestion) {
            is Note -> "note"
            is Help -> "help"
            else -> error("Unreachable")
        }
        return buildString {
            if (suggestion.labels.size == 0) {
                append(Ansi.CYAN)
                append(" ".repeat(maxLineStrLen))
                append(" = ${Ansi.DEFAULT}$identify: ${suggestion.text}\n")
            } else {
                append("$identify: ${suggestion.text}\n")
                // 按文件分组（主标签所在文件在最前）
                val groups = groupByFile(suggestion.labels)
                when (suggestion) {
                    is Note -> {
                        renderNote(map, suggestion, groups, maxLineStrLen)
                    }

                    is Help -> {
                        renderHelp(map, groups, primaryLine, maxLineStrLen)
                    }
                }
            }
        }
    }

    private fun StringBuilder.renderNote(
        map: SourceMap,
        suggestion: Suggestion,
        groups: Seq<Seq<Label>>,
        maxLineStrLen: Int
    ) {
        val primary = suggestion.labels[0]
        val primaryFile = map.getSourceFile(primary.span.index())
        if (primaryFile != null) {
            append(renderSnippet(primaryFile, groups[0], "-->", maxLineStrLen))
        }

        for (secondary in groups.iterator().also { it.next() }) {
            val secondaryFile = map.getSourceFile(secondary.first().span.index()) ?: continue
            append(renderSnippet(secondaryFile, secondary, ":::", maxLineStrLen))
        }
    }

    private fun StringBuilder.renderHelp(
        map: SourceMap,
        groups: Seq<Seq<Label>>,
        primaryLine: Int,
        maxLineStrLen: Int
    ) {
        for (labels in groups) {
            for (label in labels) {
                val file = map.getSourceFile(label.span.index()) ?: continue
                val line = file.getLine(label.span.start())
                val col = file.getCol(label.span.start())
                val lineNumStr = (line + 1).toString()
                if (line != primaryLine) {
                    val colStr = (col + 1).toString()
                    append(" ".repeat(maxLineStrLen))
                    append("--> ${file.relativePath}:$lineNumStr:$colStr\n")
                }
                append("${renderBlank(maxLineStrLen)}\n")
                val lineString = file.getLineString(line)
                when (label.style) {
                    LabelStyle.Insert -> {
                        append(" ".repeat(maxLineStrLen - lineNumStr.length))
                        append(Ansi.CYAN + lineNumStr + " ~ " + Ansi.DEFAULT)
                        append(lineString.replaceRange(col, col, label.text))
                        append("\n")
                        append(renderBlank(maxLineStrLen))
                        append(" ".repeat(col) + "+".repeat(label.text.length) + "\n")
                    }

                    LabelStyle.Delete -> {
                        append(" ".repeat(maxLineStrLen - lineNumStr.length))
                        append(Ansi.CYAN + lineNumStr + " ~ " + Ansi.DEFAULT)
                        append(lineString)
                        append("\n")
                        append(renderBlank(maxLineStrLen))
                        append(" ".repeat(col) + "-".repeat(markLen(file, label.span)) + "\n")
                    }

                    LabelStyle.Replace -> {
                        append(" ".repeat(maxLineStrLen - lineNumStr.length))
                        append(Ansi.RED + lineNumStr + " - " + Ansi.DEFAULT)
                        append(
                            lineString.replaceRange(
                                col,
                                col + markLen(file, label.span),
                                Ansi.B_RED + lineString.substring(col, col + markLen(file, label.span)) + Ansi.DEFAULT,
                            ) + "\n"
                        )

                        append(" ".repeat(maxLineStrLen - lineNumStr.length))
                        append(Ansi.GREEN + lineNumStr + " + " + Ansi.DEFAULT)
                        append(
                            lineString.replaceRange(
                                col,
                                col + markLen(file, label.span),
                                Ansi.B_GREEN + label.text + Ansi.DEFAULT,
                            ) + "\n"
                        )
                        append(renderBlank(maxLineStrLen))
                    }

                    else -> error("Unreachable")
                }
            }
        }
    }

    /** 计算所有标签中行号的最大位数（代码片段左侧的宽度） */
    private fun maxLineStrLen(map: SourceMap, labels: Seq<Label>, suggestions: Seq<Suggestion>): Int {
        var len = 1
        for (label in labels) {
            val sourceFile = map.getSourceFile(label.span.index()) ?: continue
            len = max(len, (sourceFile.getLine(label.span.end()) + 1).toString().length)
        }
        for (suggestion in suggestions) {
            for (label in suggestion.labels) {
                val sourceFile = map.getSourceFile(label.span.index()) ?: continue
                len = max(len, (sourceFile.getLine(label.span.end()) + 1).toString().length)
            }
        }
        return len
    }

    /** 按文件索引分组，保持首次出现顺序（主标签所在文件在最前） */
    private fun groupByFile(labels: Seq<Label>): Seq<Seq<Label>> {
        val groups = Seq<Seq<Label>>(1)
        val groupIndexByFile = IntIntMap(2)
        for (label in labels) {
            val fileIndex = label.span.index()
            val groupIndex = groupIndexByFile.get(fileIndex, -1)
            if (groupIndex == -1) {
                groupIndexByFile.put(fileIndex, groups.size)
                val group = Seq<Label>(2)
                group.add(label)
                groups.add(group)
            } else {
                groups.get(groupIndex).add(label)
            }
        }
        return groups
    }

    /** 计算标签下划线长度（按字符列，至少 1；多行 span 截断到主标签所在行） */
    private fun markLen(file: SourceFile, span: Span): Int {
        val start = span.start()
        val line = file.getLine(start)
        val lineStart = start - file.getCol(start)
        val lineEnd = lineStart + file.getLineString(line).length
        val end = min(span.end(), lineEnd)
        return max(1, end - start)
    }
}