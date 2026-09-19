package mlogix.compiler.ast

import arc.struct.Seq
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.span.Spanned
import mlogix.compiler.core.symbol.DefId
import mlogix.compiler.core.token.Token

//Statement
abstract class Stmt(span: Span) : ASTNode(span) {
    data class Program(override val span: Span, val stmts: Seq<Stmt>) : Stmt(span)

    data class UseStmt(override val span: Span, val item: UseItem) : Stmt(span) {

        abstract class UseItem(open val span: Span) : Spanned {
            override fun span(): Span {
                return this.span
            }
        }

        data class Single(override val span: Span, val path: Seq<Expr.Identifier>) : UseItem(span)

        // *
        data class All(override val span: Span, val path: Seq<Expr.Identifier>) : UseItem(span)

        // **
        data class Recursion(override val span: Span, val path: Seq<Expr.Identifier>) : UseItem(span)

        // {...}
        data class Multi(override val span: Span, val path: Seq<Expr.Identifier>, val items: Seq<UseItem>) :
            UseItem(span)
    }

    data class BlockStmt(override val span: Span, val stmts: Seq<Stmt>) : Stmt(span)

    data class ExprStmt(override val span: Span, val expr: Expr) : Stmt(span)

    data class IfStmt(override val span: Span, val condition: Expr, val thenBranch: Stmt?, val elseBranch: Stmt?) :
        Stmt(span)

    data class MatchStmt(override val span: Span, val scrutinee: Expr, val branches: Seq<MatchBranch>?) : Stmt(span) {
        data class MatchBranch(val span: Span, val pattern: Expr, val body: Stmt?) : Spanned {
            override fun span(): Span {
                return this.span
            }
        }
    }

    data class ForStmt(
        override val span: Span,
        val flag: Expr.Identifier?,
        val varDecl: Expr.Identifier?,
        val expr: Expr?,
        val body: Stmt?
    ) : Stmt(span)

    data class WhileStmt(override val span: Span, val flag: Expr.Identifier?, val expr: Expr, val body: Stmt?) :
        Stmt(span)

    data class BreakStmt(override val span: Span, val flag: Expr.Identifier?) : Stmt(span)

    data class ContinueStmt(override val span: Span, val flag: Expr.Identifier?) : Stmt(span)

    data class FnStmt(
        override val span: Span,
        val name: Token?,
        val typeParams: Seq<Expr.Identifier>?,
        val params: Seq<Expr>?,
        val results: Seq<Expr>?,
        val body: Stmt?
    ) : Stmt(span) {
        /** 由 Resolver 填充：此函数定义对应的 [DefId]（未声明/解析失败时为 null） */
        var defId: DefId? = null
    }

    data class ReturnStmt(override val span: Span, val expr: Expr?) : Stmt(span)

    data class AssignStmt(override val span: Span, val `var`: Expr, val operator: Token, val value: Expr) : Stmt(span)

    data class SetVarStmt(override val span: Span, val `var`: Expr, val assignStmt: AssignStmt?) : Stmt(span)

    data class StructStmt(
        override val span: Span,
        val name: Expr.Identifier,
        val typeParams: Seq<Expr.Identifier>?,
        /** 字段**声明** */
        val fields: Seq<ASTNode>,
        val methods: Seq<FnStmt>
    ) : Stmt(span)

    /**
     * 枚举声明（Rust 风格），变体用 `.` 访问：`Color.Red`、`Option.Some(1)`、`Shape.Rect(1.0, 2.0)`。
     *
     * ```mlx
     * enum Option<T> {
     *     None
     *     Some(T)
     *     Point { x: Num, y: Num }
     * }
     * ```
     *
     * 三种变体形态（载荷都按声明顺序传入，见 [fieldsOf]）：
     * - 单元变体 `Red` —— 本身就是值；
     * - 元组变体 `Rgb(Num, Num, Num)` —— 构造器；
     * - 结构体变体 `Named { name: Str, alpha: Num }` —— 构造器（字段名只用于诊断与文档，
     *   调用处不支持具名实参）。
     */
    data class EnumStmt(
        override val span: Span,
        val name: Expr.Identifier,
        val typeParams: Seq<Expr.Identifier>?,
        val variants: Seq<EnumVariant>
    ) : Stmt(span) {
        /** 由 Resolver 填充：此枚举定义对应的 [DefId]（未声明/解析失败时为 null） */
        var defId: DefId? = null

        /** 变体的载荷字段；单元变体没有载荷 */
        fun fieldsOf(variant: EnumVariant): Seq<Expr> = when (variant) {
            is EnumVariant.Unit -> Seq(0)
            is EnumVariant.Tuple -> variant.fields
            is EnumVariant.Struct -> variant.fields
        }

        /** 一个枚举变体 */
        sealed class EnumVariant(open val span: Span) : Spanned {
            override fun span(): Span {
                return this.span
            }

            /** 变体名（`Color.Red` 中的 `Red`） */
            abstract val name: Expr.Identifier

            /** 单元变体 `Red` */
            data class Unit(override val span: Span, override val name: Expr.Identifier) : EnumVariant(span)

            /** 元组变体 `Rgb(Num, Num, Num)`：载荷是类型表达式 */
            data class Tuple(
                override val span: Span,
                override val name: Expr.Identifier,
                val fields: Seq<Expr>
            ) : EnumVariant(span)

            /** 结构体变体 `Named { name: Str }`：载荷是 `字段名 : 类型` 注解 */
            data class Struct(
                override val span: Span,
                override val name: Expr.Identifier,
                val fields: Seq<Expr>
            ) : EnumVariant(span)
        }
    }
}
