package mlogix.compiler.ast

import arc.struct.Seq
import mlogix.compiler.core.symbol.DefId
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.span.Span

//Expression
abstract class Expr(span: Span) : ASTNode(span) {
    /**
     * 字面量
     */
    data class Literal(val token: Token) : Expr(token.span)

    /**
     * 标识符
     */
    data class Identifier(val token: Token) : Expr(token.span) {
        /**
         * 由 Resolver 填充：此标识符解析到的定义句柄。
         * 未声明（解析失败）时为 null。注意：不参与 data class 的 equals/hashCode。
         */
        var defId: DefId? = null
    }

    /**
     * 元组
     */
    data class Tuple(override val span: Span, val elements: Seq<Expr>) : Expr(span)

    /**
     * 需要保证annotations.size > 0
     */
    data class Annotation(
        val expr: Expr,
        val annotations: Seq<Expr>
    ) : Expr(Span.between(expr, annotations[annotations.size - 1]))

    /**
     * 一元运算
     */
    data class Unary(val operator: Token, val expr: Expr) : Expr(Span.between(operator.span, expr.span))

    /**
     * 二元运算
     */
    data class Binary(val left: Expr, val operator: Token, val right: Expr) : Expr(
        Span.between(
            left.span, right.span
        )
    )

    /**
     * 数组
     */
    data class Array(override val span: Span, val elements: Seq<Expr>) : Expr(span)

    /**
     * 索引
     */
    data class Index(override val span: Span, val list: Expr, val index: Expr) : Expr(span)


    /**
     * 范围
     */
    data class Range(override val span: Span, val left: Expr?, val operator: Token, val right: Expr?) : Expr(span)

    /**
     * 函数调用 func(...)
     */
    data class Call(override val span: Span, val callee: Expr, val arguments: Seq<Expr>) : Expr(span)

    /**
     * 获取字段 type.field  type.func
     */
    data class Get(val obj: Expr, val field: Expr) : Expr(Span.between(obj.span, field.span))

    /**
     * 错误恢复占位符
     */
    data class ErrorExpr(override val span: Span) : Expr(span)
}