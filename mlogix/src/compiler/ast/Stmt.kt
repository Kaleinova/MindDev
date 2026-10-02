package mlogix.compiler.ast

import arc.struct.Seq
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.span.Spanned
import mlogix.compiler.core.symbol.DefId
import mlogix.compiler.core.token.Token

//Statement
abstract class Stmt(span: Span) : ASTNode(span) {
    data class Program(override val span: Span, val stmts: Seq<Stmt>) : Stmt(span)

    data class Use(override val span: Span, val item: UseItem) : Stmt(span) {

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

    data class Block(override val span: Span, val stmts: Seq<Stmt>) : Stmt(span)

    data class ExprStmt(override val span: Span, val expr: Expr) : Stmt(span)

    data class If(override val span: Span, val condition: Expr, val thenBranch: Stmt?, val elseBranch: Stmt?) :
        Stmt(span)

    data class Match(override val span: Span, val scrutinee: Expr, val branches: Seq<MatchBranch>?) : Stmt(span) {
        data class MatchBranch(val span: Span, val pattern: Pattern, val body: Stmt?) : Spanned {
            override fun span(): Span {
                return this.span
            }
        }
    }

    data class For(
        override val span: Span,
        val flag: Expr.Identifier?,
        val varDecl: Expr.Identifier?,
        val expr: Expr?,
        val body: Stmt?
    ) : Stmt(span)

    data class While(override val span: Span, val flag: Expr.Identifier?, val expr: Expr, val body: Stmt?) :
        Stmt(span)

    data class Break(override val span: Span, val flag: Expr.Identifier?) : Stmt(span)

    data class Continue(override val span: Span, val flag: Expr.Identifier?) : Stmt(span)

    data class Fn(
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

    data class Return(override val span: Span, val expr: Expr?) : Stmt(span)

    data class Assign(override val span: Span, val `var`: Expr, val operator: Token, val value: Expr) : Stmt(span)

    data class SetVar(override val span: Span, val `var`: Expr, val assign: Assign?) : Stmt(span)

    data class Struct(
        override val span: Span,
        val name: Expr.Identifier,
        val typeParams: Seq<Expr.Identifier>?,
        /** 字段**声明** */
        val fields: Seq<StructField>,
        val methods: Seq<Fn>
    ) : Stmt(span) {
        /** 由 Resolver 填充：此结构体定义对应的 [DefId]（未声明/解析失败时为 null） */
        var defId: DefId? = null

        /**
         * 结构体字段声明 `名字 [: 类型] [= 默认值]`。
         *
         * 三部分都可选性受约束（由 Parser 检查）：至少要写出类型或默认值之一——
         * 两者都不写就没有任何信息可以推断字段类型。
         *
         * 默认值让字段在构造时**可省略**（`struct Point { x: Num = 0, y: Num = 0 }` 可写 `Point(1)`），
         * 但默认值只在「该字段缺席」时参与检查，不能引用同结构体的其它字段
         * （避免字段默认值之间形成依赖环，语义上要求默认值自成一体）。
         */
        data class StructField(
            override val span: Span,
            val name: Expr.Identifier,
            /** 冒号后的类型注解；省略类型时为 null（类型由默认值推断） */
            val type: Expr.Annotation?,
            /** `= 默认值`；没有默认值时为 null（该字段构造时必须传入） */
            val default: Expr?,
        ) : ASTNode(span) {
            /** 由 Resolver 填充：此字段对应的 [DefId] */
            var defId: DefId? = null
        }
    }

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
    data class Enum(
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
