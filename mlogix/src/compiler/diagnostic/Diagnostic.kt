package mlogix.compiler.diagnostic

import arc.struct.ObjectMap
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
 *   [suggestion] 建议（以 `help:` 渲染，样式类似 label）。
 */
abstract class Diagnostic(
    val message: String,   // 问题描述
    val level: DiagLevel,  // 问题级别
) {
    /** 标签列表：第 1 个为主标签（`^`），其余为次级标签（`-`） */
    val labels = Seq<Label>(2)

    /** 建议列表：渲染方式类似 label */
    val suggestions = Seq<Suggestion>(1)

    fun label(span: Span, text: String): Diagnostic {
        labels.add(Label(span, text, if (labels.isEmpty) InfoStyle.PRIMARY else InfoStyle.SECONDARY))
        return this
    }

    fun label(spanned: Spanned, text: String): Diagnostic = this.label(spanned.span(), text)

    /** 添加一条建议 */
    fun suggestion(span: Spanned, text: String): Diagnostic {
        suggestions.add(Suggestion(span.span(), text))
        return this
    }

    enum class DiagLevel {
        WARNING, ERROR
    }

    /** 标签样式 */
    enum class InfoStyle(val marker: Char) {
        PRIMARY('^'),
        SECONDARY('-'),
    }

    /** 一个带位置与样式的标签 */
    data class Label(val span: Span, val text: String, val style: InfoStyle)

    /** 一条建议 */
    data class Suggestion(val span: Span, val text: String)

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
            if (sourceMap == null || labels.isEmpty) return toString()

            // 按文件分组（主标签所在文件在最前）
            val groups = groupByFile(labels)

            // --> 路径:行:列（主标签位置）
            val primary = labels.first()
            val primaryFile = sourceMap.getSourceFile(primary.span.index())
            if (primaryFile != null) {
                val line = primaryFile.getLine(primary.span.start()) + 1
                val col = primaryFile.getCol(primary.span.start()) + 1
                // 以主标签所在文件组的行号宽度填充，保证 `-->` 与下方代码片段对齐
                append(" ".repeat(maxLineDigits(primaryFile, groups.first())))
                append("--> ${primaryFile.relativePath}:$line:$col\n")
            }

            for (fileInfos in groups) {
                val file = sourceMap.getSourceFile(fileInfos.first().span.index()) ?: continue
                append(renderSnippet(file, fileInfos))
            }

            for (suggestion in suggestions) {
                append(renderSuggestion(sourceMap, suggestion))
            }
        }
    }

    override fun toString(): String = render(null)

    /** 按文件索引分组，保持首次出现顺序（主标签所在文件在最前） */
    private fun groupByFile(labels: Seq<Label>): Seq<Seq<Label>> {
        val groups = Seq<Seq<Label>>(1)
        val groupIndexByFile = ObjectMap<Int, Int>()
        for (label in labels) {
            val fileIndex = label.span.index()
            val groupIndex = groupIndexByFile.get(fileIndex)
            if (groupIndex == null) {
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

    private fun renderSnippet(file: SourceFile, labels: Seq<Label>): String {
        labels.sort(Comparator { a, b ->
            val lineDiff = file.getLine(a.span.start()) - file.getLine(b.span.start())
            if (lineDiff != 0) lineDiff
            else file.getCol(a.span.start()) - file.getCol(b.span.start())
        })

        val lineDigits = maxLineDigits(file, labels)

        return buildString {
            append("${Ansi.CYAN}${" ".repeat(lineDigits)} | ${Ansi.DEFAULT}\n")

            var index = 0
            while (index < labels.size) {
                val line = file.getLine(labels.get(index).span.start())
                val lineInfos = Seq<Label>(2)
                while (index < labels.size && file.getLine(labels.get(index).span.start()) == line) {
                    lineInfos.add(labels.get(index))
                    index++
                }
                append(renderLine(file, line, lineInfos, lineDigits))
            }
        }
    }

    /** 计算标签中行号的最大位数（代码片段左侧的宽度） */
    private fun maxLineDigits(file: SourceFile, labels: Seq<Label>): Int {
        var digits = 1
        for (label in labels) {
            digits = max(digits, (file.getLine(label.span.start()) + 1).toString().length)
        }
        return digits
    }

    private fun renderLine(file: SourceFile, line: Int, lineLabels: Seq<Label>, lineDigits: Int): String {
        val lineNum = (line + 1).toString()

        return buildString {
            // 源码行
            append("${Ansi.CYAN}${" ".repeat(lineDigits - lineNum.length)}$lineNum | ${Ansi.DEFAULT}${file.getLineString(line)}\n")

            // 标记行：主标签用 ^，次级标签用 -
            append("${Ansi.CYAN}${" ".repeat(lineDigits)} | ${Ansi.DEFAULT}")
            var prevCol = 0
            var primaryText: String? = null
            for (label in lineLabels) {
                val col = file.getCol(label.span.start())
                val length = underlineLength(file, label.span)
                append(" ".repeat(max(0, col - prevCol)))
                val color = if (label.style == InfoStyle.PRIMARY) Ansi.GREEN else Ansi.BLUE
                append("$color${label.style.marker.toString().repeat(length)}${Ansi.DEFAULT}")
                prevCol = max(prevCol, col + length)
                if (label.style == InfoStyle.PRIMARY && label.text.isNotEmpty()) primaryText = label.text
            }
            if (primaryText != null) {
                append(" ${Ansi.GREEN}$primaryText${Ansi.DEFAULT}")
            }
            append('\n')

            // 次级标签续行
            for (label in lineLabels) {
                if (label.style != InfoStyle.SECONDARY || label.text.isEmpty()) continue
                val col = file.getCol(label.span.start())
                append("${Ansi.CYAN}${" ".repeat(lineDigits)} | ${Ansi.DEFAULT}")
                    .append(" ".repeat(col))
                    .append("${Ansi.BLUE}|${Ansi.DEFAULT} ${label.text}\n")
            }
        }
    }

    private fun renderSuggestion(sourceMap: SourceMap, suggestion: Suggestion): String {
        val file = sourceMap.getSourceFile(suggestion.span.index()) ?: return ""
        val line = file.getLine(suggestion.span.start())
        val lineNum = (line + 1).toString()
        val lineDigits = lineNum.length

        return buildString {
            append("${Ansi.CYAN}  = ${Ansi.DEFAULT}help: ${suggestion.text}\n")
            append("${Ansi.CYAN}${" ".repeat(lineDigits)} | ${Ansi.DEFAULT}${file.getLineString(line)}\n")
            val col = file.getCol(suggestion.span.start())
            val length = underlineLength(file, suggestion.span)
            append("${Ansi.CYAN}${" ".repeat(lineDigits)} | ${Ansi.DEFAULT}${" ".repeat(col)}${Ansi.GREEN}${"^".repeat(length)}${Ansi.DEFAULT}\n")
        }
    }

    /** 计算标签下划线长度（按字符列，至少 1；多行 span 截断到主标签所在行） */
    private fun underlineLength(file: SourceFile, span: Span): Int {
        val start = span.start()
        val line = file.getLine(start)
        val lineStart = start - file.getCol(start)
        val lineEnd = lineStart + file.getLineString(line).length
        val end = min(span.end(), lineEnd)
        return max(1, end - start)
    }
}