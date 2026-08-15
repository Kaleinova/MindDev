package mlogix.compiler.diagnostic

import arc.struct.IntIntMap
import arc.struct.IntSeq
import arc.struct.Seq
import mlogix.compiler.core.SourceMap
import mlogix.compiler.core.SourceMap.SourceFile
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
            append("\n")
        }
    }


    override fun toString(): String = render(null)


    /**
     * 渲染一个文件
     */
    private fun renderSnippet(file: SourceFile, labels: Seq<Label>, fileMark: String, maxLineStrLen: Int): String {
        return buildString {
            val primary = labels[0]
            val lineStr = (file.getLine(primary.span.start()) + 1).toString()
            val colStr = (file.getCol(primary.span.start()) + 1).toString()
            append(" ".repeat(maxLineStrLen))
            append("$fileMark ${file.relativePath}:$lineStr:$colStr\n")

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
        val lineStr = (line + 1).toString()
        return buildString {
            // 源码行
            append(
                "${Ansi.CYAN}${" ".repeat(maxLineStrLen - lineStr.length)}$lineStr | ${Ansi.DEFAULT}${
                    file.getLineString(line)
                }\n"
            )
        }
    }

    private fun renderLine(file: SourceFile, line: Int, originalLabels: Seq<Label>, maxLineStrLen: Int): String {
        val lineStr = (line + 1).toString()

        return buildString {
            // 源码行
            append(
                "${Ansi.CYAN}${" ".repeat(maxLineStrLen - lineStr.length)}$lineStr | ${Ansi.DEFAULT}${
                    file.getLineString(line)
                }\n"
            )

            // 备餐
            val labels = Seq<Label>(originalLabels.size)
            val lens = IntSeq(originalLabels.size)
            val spaces = IntSeq(originalLabels.size)
            var curCol = 0
            for (label in originalLabels) {
                val col = file.getDisplayCol(label.span.start())
                val len = markLen(file, label.span)
                val space = col - curCol
                if (space < 0) continue
                labels.add(label)
                lens.add(len)
                spaces.add(space)
                curCol = col + len
            }

            // ┃  ^ - ^ labels[size-1]text
            append(renderBlank(maxLineStrLen))
            for ((i, label) in labels.withIndex()) {
                append(" ".repeat(spaces[i]))
                append(label.style.marker.toString().repeat(lens[i]))
            }
            append(" ${labels.last().text}\n")

            // ┃  | |
            // ┃  | labels[size-2].text
            // ┃  |
            // ┃  labels[size-3].text
            for (i in labels.size - 2 downTo 0) {
                val text = labels[i].text
                if (text.isEmpty()) continue

                // ┃  | |
                append(renderBlank(maxLineStrLen))
                for (j in 0..i) {
                    append(" ".repeat(spaces[j]))
                    append("|")
                }
                append("\n")

                // ┃ | labels[size-2].text
                append(renderBlank(maxLineStrLen))
                for (j in 0..i - 1) {
                    append(" ".repeat(spaces[j]))
                    append("|")
                }
                append(" ".repeat(spaces[i]))
                append(text)
                append("\n")
            }

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
                val lineStr = (line + 1).toString()
                if (line != primaryLine) {
                    val colStr = (col + 1).toString()
                    append(" ".repeat(maxLineStrLen))
                    append("--> ${file.relativePath}:$lineStr:$colStr\n")
                }
                append("${renderBlank(maxLineStrLen)}\n")
                when (label.style) {
                    LabelStyle.Insert -> {
                        append(" ".repeat(maxLineStrLen - lineStr.length))
                        append(Ansi.CYAN + lineStr + " ~ " + Ansi.DEFAULT)
                        append(file.getLineString(line).replaceRange(col, col, label.text))
                        append("\n")
                        append(renderBlank(maxLineStrLen))
                        append(" ".repeat(col) + "+".repeat(label.text.length) + "\n")
                    }

                    LabelStyle.Delete -> {
                        append(" ".repeat(maxLineStrLen - lineStr.length))
                        append(Ansi.CYAN + lineStr + " ~ " + Ansi.DEFAULT)
                        append(file.getLineString(line))
                        append("\n")
                        append(renderBlank(maxLineStrLen))
                        append(" ".repeat(col) + "-".repeat(markLen(file, label.span)) + "\n")
                    }

                    LabelStyle.Replace -> {
                        append(" ".repeat(maxLineStrLen - lineStr.length))
                        append(Ansi.RED + lineStr + " - " + Ansi.DEFAULT)
                        append(file.getLineString(line) + "\n")

                        append(" ".repeat(maxLineStrLen - lineStr.length))
                        append(Ansi.GREEN + lineStr + " + " + Ansi.DEFAULT)
                        append(
                            file.getLineString(line).replaceRange(
                                col,
                                col + markLen(file, label.span),
                                label.text
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