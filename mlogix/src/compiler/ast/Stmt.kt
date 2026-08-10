package mlogix.compiler.ast

import arc.struct.Seq
import mlogix.compiler.core.symbol.DefId
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.span.Spanned

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
        val parameters: Seq<Expr>?,
        val results: Seq<Expr>?,
        val body: Stmt?
    ) : Stmt(span) {
        /** 由 Resolver 填充：此函数定义对应的 [DefId]（未声明/解析失败时为 null） */
        var defId: DefId? = null
    }

    data class ReturnStmt(override val span: Span, val expr: Expr?) : Stmt(span)

    data class AssignStmt(override val span: Span, val `var`: Expr, val operator: Token, val value: Expr) : Stmt(span)

    data class SetVarStmt(override val span: Span, val `var`: Expr, val assignStmt: Stmt?) : Stmt(span)
}