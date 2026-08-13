package mlogix.compiler.ast

import arc.struct.Seq
import mlogix.compiler.core.SourceMap.SourceFile
import mlogix.compiler.ast.Stmt.MatchStmt.MatchBranch
import mlogix.compiler.ast.Stmt.UseStmt.UseItem
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.span.Spanned
import mlogix.util.Ansi
import kotlin.math.max

object ASTPrinter {
    private const val STMT_COLOR: String = Ansi.YELLOW   // 蓝色 - 语句
    private const val EXPR_COLOR: String = Ansi.BLUE     // 青色 - 表达式
    private const val TOKEN_COLOR: String = Ansi.CYAN    // 黄色 - Token
    private const val VALUE_COLOR: String = Ansi.GREEN   // 绿色 - 值
    private const val FIELD_COLOR: String = Ansi.DEFAULT // 白色 - 字段
    private const val LIST_COLOR: String = Ansi.MAGENTA  // 洋红色 - 列表连接线
    private const val LINE_COLOR: String = Ansi.DEFAULT  // 白色 - 连接线后恢复默认

    // 连接线样式
    private const val INDENT_BLANK = "   $LINE_COLOR"
    private const val CONNECTOR_LAST = "└──$LINE_COLOR"
    private const val CONNECTOR_MID = "├──$LINE_COLOR"
    private const val VERTICAL_LINE = "│  $LINE_COLOR"

    // 是否缩进
    private var indentEnabled = false

    private lateinit var sourceFile: SourceFile

    fun print(node: ASTNode, sourceFile: SourceFile) {
        indentEnabled = false // Program前不需要缩进
        ASTPrinter.sourceFile = sourceFile
        print(node, "", true)
    }

    private fun print(node: Any?, indent: String?, isLast: Boolean) {
        if (node == null) {
            printLine(indent, isLast, "null")
            return
        }

        // 处理不同类型
        when (node) {
            is Stmt -> printNode(node, indent, isLast, STMT_COLOR)
            is Expr -> printNode(node, indent, isLast, EXPR_COLOR)
            is UseItem -> printNode(node, indent, isLast, EXPR_COLOR)
            is MatchBranch -> printNode(node, indent, isLast, EXPR_COLOR)
            is Token -> printLine(indent, isLast, TOKEN_COLOR + node + Ansi.DEFAULT)
            is Seq<*> -> printList(node, indent, isLast)
            is Array<*> -> printArray(node, indent, isLast)
            else -> printLine(indent, isLast, node.toString())
        }
    }

    private fun printNode(node: Spanned, indent: String?, isLast: Boolean, color: String?) {
        // 打印节点类型名称
        val start = node.span().start()
        val end = max(node.span().end(), start)

        val startLine = sourceFile.getLine(start)
        val endLine = sourceFile.getLine(end)

        val startLineString = sourceFile.getLineString(startLine)
        val endLineString = sourceFile.getLineString(endLine)

        val startCol = sourceFile.getCol(start)
        val endCol = sourceFile.getCol(end)

        val text = buildString {
            // 前缀
            append("$color${node::class.simpleName}")
            append("$VALUE_COLOR[$start,$end) ")
            append("${Ansi.DEFAULT}${"$startLine"}")
            append("${Ansi.BLACK}${Ansi.B_CYAN}")
            append(startLineString.substring(0, startCol))

            if (startLine == endLine) {
                append("${Ansi.B_YELLOW}${startLineString.substring(startCol, endCol)}")
                append("${Ansi.B_CYAN}${startLineString.substring(endCol)}")
            } else {
                append("${Ansi.B_YELLOW}${startLineString.substring(startCol)}")
                append("${Ansi.DEFAULT}${" $endLine"}")
                append("${Ansi.BLACK}${Ansi.B_YELLOW}${endLineString.substring(0, endCol)}")
                append("${Ansi.B_CYAN}${endLineString.substring(endCol)}")
            }
            append(Ansi.DEFAULT)
        }

        printLine(indent, isLast, text)

        indentEnabled = true

        // 计算新的缩进
        val newIndent = indent + (if (isLast) INDENT_BLANK else VERTICAL_LINE + FIELD_COLOR)

        // 获取所有字段
        val fields = node::class.java.declaredFields
        if (fields.isEmpty()) return

        // 打印所有字段
        fields.forEachIndexed { i, field ->
            val fieldIsLast = (i == fields.size - 1)
            field.isAccessible = true

            try {
                val value = field.get(node)
                printField(field.name, value, newIndent, fieldIsLast)
            } catch (e: IllegalAccessException) {
                printLine(newIndent, fieldIsLast, "ERROR: ${e.message}")
            }
        }
    }

    private fun printField(fieldName: String?, value: Any?, indent: String?, isLast: Boolean) {
        if (value == null) {
            printLine(indent, isLast, "$FIELD_COLOR$fieldName: ${VALUE_COLOR}null${Ansi.DEFAULT}")
            return
        } else if (value is Span) {
            // 只有MatchBranch的span会跑到这来，忽略掉
            return
        } else if ((value is Seq<*> && value.isEmpty) || (value is Array<*> && value.isEmpty())) {
            printLine(indent, isLast, "$FIELD_COLOR$fieldName: $LIST_COLOR[]${Ansi.DEFAULT}")
            return
        }

        // 对于简单类型直接打印
        if (value is Boolean || value is Number || value is String || value is Token) {
            printLine(indent, isLast, "$FIELD_COLOR$fieldName: $VALUE_COLOR$value${Ansi.DEFAULT}")
            return
        }

        // 对于复杂类型递归打印
        printLine(indent, isLast, "$fieldName:")
        indentEnabled = false
        print(value, indent + (if (isLast) INDENT_BLANK else VERTICAL_LINE + FIELD_COLOR), true)
    }

    private fun printList(list: Seq<*>, indent: String?, isLast: Boolean) {
        if (list.isEmpty) {
            printLine(indent, isLast, "List[]")
            return
        }

        printLine(indent, isLast, "${LIST_COLOR}List${Ansi.DEFAULT}")
        val newIndent = indent + LIST_COLOR

        indentEnabled = true
        for ((i, element) in list.withIndex()) {
            val itemIsLast = (i == list.size - 1)
            print(element, newIndent, itemIsLast)
        }
    }

    private fun <T> printArray(array: Array<T>, indent: String?, isLast: Boolean) {
        if (array.isEmpty()) {
            printLine(indent, isLast, "Array[]")
            return
        }

        printLine(indent, isLast, "${LIST_COLOR}Array${Ansi.DEFAULT}")
        val newIndent = indent + LIST_COLOR

        indentEnabled = true
        for (i in array.indices) {
            val itemIsLast = (i == array.size - 1)
            print(array[i], newIndent, itemIsLast)
        }
    }

    private fun printLine(indent: String?, isLast: Boolean, text: String?) {
        print(indent)
        if (indentEnabled) print(if (isLast) CONNECTOR_LAST else CONNECTOR_MID)
        println(text)
    }
}
