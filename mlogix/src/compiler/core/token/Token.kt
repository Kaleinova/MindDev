package mlogix.compiler.core.token

import mlogix.compiler.core.span.Span
import mlogix.compiler.core.span.Spanned

data class Token(
    val span: Span,
    val type: TokenType,
    val literal: Any? = null
) : Spanned {

    override fun toString(): String =
        "Token{${type.name},${span},$literal}"

    fun toSimpleString(): String =
        "Token(${type.name},$literal)"

    override fun span(): Span {
        return span
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Token

        if (type != other.type) return false
        if (literal != other.literal) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + literal.hashCode()
        return result
    }
}