package mlogix.compiler.ast

import arc.struct.Seq
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.span.Spanned
import mlogix.compiler.core.symbol.DefId

/**
 * match 分支的模式（pattern）。
 *
 * 目前支持三种形态（字面量/元组/范围/or/guard 尚未支持，见 [mlogix.compiler.passes.parsing.Parser.pattern]）：
 * - [Wildcard]：`_`，匹配任何值、不绑定；
 * - [Binding]：裸标识符 `x`，匹配任何值并把值绑到**新变量**（Rust 风格，作用域限于该分支体）；
 *   想匹配枚举变体必须写全路径 `枚举名.变体`；
 * - [Variant]：`Color.Red` / `Option.Some(x)` / `Shape.Rect(w, h)`，载荷按声明顺序、可嵌套。
 *
 * 注意：模式里**没有**「与已有变量比较」这一形态（裸标识符一律是绑定），
 * 因此绑定名与被匹配枚举的变体同名时会告警（防 rustc 那个经典坑）。
 */
sealed class Pattern(open val span: Span) : Spanned {
    override fun span(): Span {
        return this.span
    }

    /** 通配符 `_` */
    data class Wildcard(override val span: Span) : Pattern(span)

    /** 绑定：裸标识符 */
    data class Binding(override val span: Span, val name: Expr.Identifier) : Pattern(span) {
        /** 由 Resolver 填充：此绑定对应的新符号（分支体作用域内可见） */
        var defId: DefId? = null
    }

    /**
     * 枚举变体模式：`枚举名.变体` 或 `枚举名.变体(模式, ...)`。
     *
     * [path] 复用表达式里的 `Expr.Get`（`EnumName.Variant`），Resolver 用同一套变体表解析并填 [DefId]；
     * [args] 为空表示不带载荷（单元变体，或漏写载荷——后者由类型检查报「载荷数量不符」）。
     */
    data class Variant(override val span: Span, val path: Expr.Get, val args: Seq<Pattern>) : Pattern(span)
}
