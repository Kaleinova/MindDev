package mlogix.compiler.ast

import arc.struct.Seq
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.symbol.DefId
import mlogix.compiler.core.token.Token

//Expression
abstract class Expr(span: Span) : ASTNode(span) {
    /**
     * 字面量
     */
    data class Literal(val token: Token) : Expr(token.span())

    /**
     * 标识符
     */
    data class Identifier(override val span: Span, val token: Token, val typeArgs: Seq<Identifier>? = null) : Expr(
        span
    ) {
        constructor(token: Token) : this(token.span, token)

        /**
         * 由 Resolver 填充：此标识符解析到的定义句柄。
         * 未声明（解析失败）时为 null。注意：不参与 data class 的 equals/hashCode。
         */
        var defId: DefId? = null
    }

    /**
     * 隐式接收者 `self`：struct 方法体内省略不写，由编译器代管。
     *
     * 源码里写不出 `self`（它不是标识符、也不进作用域），因此它**不是** [Identifier]：
     * 单独一个节点才能让「方法体的形参列表」与「方法调用的实参列表」按下标严格对齐，
     * 也让类型系统能一眼认出「这个形参由调用点传入接收者」而不是「由实参传入」。
     *
     * 两种用法（都由 Resolver 填 [instanceDefId]）：
     * - **形参标记**：方法声明的形参列表里代表接收者那一位（用户显式写的，或
     *   TypeInferencer 在首位补齐的），类型永远是所属 struct；
     * - **隐式字段接收者**：方法体里直接写字段名（`x`、`y`）时，字段引用被包成
     *   `Get(SelfRef, 字段名)`，于是字段读取与 `self.x` 收敛到同一条推断路径。
     */
    data class SelfRef(override val span: Span) : Expr(span) {
        /** 由 Resolver 填充：所属 struct 类型的定义句柄 */
        var instanceDefId: DefId? = null
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
    data class Unary(val operator: Token, val expr: Expr) : Expr(Span.between(operator, expr))

    /**
     * 二元运算
     */
    data class Binary(val left: Expr, val operator: Token, val right: Expr) : Expr(
        Span.between(left, right)
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
     *
     * [callee] 是 `var`：名称解析期要**就地**改写被调用者（方法体里裸写的方法名 →
     * 隐式 `self.方法名`）。若改成新建 [Call] 节点，外层 AST 仍指向旧节点，
     * 改写结果就传不到后续阶段（类型推断只看得到旧的裸标识符）。
     * 与 `Stmt.Fn.params` 同理：解析期就地补/改，不重挂整棵树。
     */
    data class Call(override val span: Span, var callee: Expr, val args: Seq<Expr>) :
        Expr(span)

    /**
     * 获取字段 type.field  type.func
     */
    data class Get(val obj: Expr, val field: Expr) : Expr(Span.between(obj, field))

    /**
     * 错误恢复占位符
     */
    data class ErrorExpr(override val span: Span) : Expr(span)
}