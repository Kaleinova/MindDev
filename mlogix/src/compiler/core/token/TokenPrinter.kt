package mlogix.compiler.core.token

import arc.struct.Seq
import mlogix.compiler.core.SourceMap
import mlogix.util.Ansi
import kotlin.math.max

object TokenPrinter {
    fun print(tokens: Seq<Token>, sourceFile: SourceMap.SourceFile) {
        var maxLineNumLen = 1
        var maxTypeStrLen = 0
        var maxLiteralStrLen = 0
        tokens.forEach {
            maxLineNumLen = max(maxLineNumLen, (sourceFile.getLine(it.span.start()) + 1).toString().length)
            maxTypeStrLen = max(maxTypeStrLen, it.type.toString().length)
            maxLiteralStrLen = max(maxLiteralStrLen, it.literal?.toString()?.length ?: 0)
        }
        tokens.forEach {
            println(buildString {
                val start = it.span.start()
                val line = sourceFile.getLine(start)

                val lineNumStr = (line + 1).toString()
                append(" ".repeat(maxLineNumLen - lineNumStr.length) + lineNumStr)
                append(Ansi.CYAN + " | " + Ansi.DEFAULT)

                val typeStr = it.type.toString()
                append(" ".repeat(maxTypeStrLen - typeStr.length) + typeStr)
                append(Ansi.CYAN + " | " + Ansi.DEFAULT)

                val literalStr = it.literal?.toString() ?: ""
                append(" ".repeat(maxLiteralStrLen - literalStr.length) + literalStr)
                append(Ansi.CYAN + " | " + Ansi.DEFAULT)

                val lineStr = sourceFile.getLineStringWithNewline(line)
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                val startCol = sourceFile.getCol(start)
                val endCol = if (it.type != TokenType.NEWLINE)
                    sourceFile.getCol(it.span.end())
                else startCol + it.span.len()

                append(Ansi.BLACK)
                append(Ansi.B_CYAN + lineStr.substring(0, startCol))
                append(Ansi.B_YELLOW + lineStr.substring(startCol, endCol))
                append(Ansi.B_CYAN + lineStr.substring(endCol))
                append(Ansi.DEFAULT)
            })
        }
    }
}