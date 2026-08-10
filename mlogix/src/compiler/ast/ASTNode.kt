package mlogix.compiler.ast

import mlogix.compiler.core.span.Span
import mlogix.compiler.core.span.Spanned

abstract class ASTNode(open val span: Span) : Spanned {

    override fun span(): Span {
        return span
    }
}
