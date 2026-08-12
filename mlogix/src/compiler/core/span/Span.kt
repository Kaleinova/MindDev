package mlogix.compiler.core.span

class Span(
    val start: Int,  // 起始字符偏移量
    val end: Int     // 末尾字符偏移量+1
) : Spanned {

    override fun span(): Span = this

    /**
     * ⚠︎WARNING: 为了减少ASTNode相等判断的样板代码，本方法忽略Span的[start]和[end]属性
     *
     * 要使用不忽略属性的方法，请使用[toStructuralString]
     */
    override fun toString(): String = "Span"

    fun toStructuralString(): String = "Span{$start,$end}"

    /**
     * ⚠︎WARNING: 为了减少ASTNode相等判断的样板代码，本方法忽略Span的[start]和[end]属性
     *
     * 要使用不忽略属性的相等判断，请使用[structuralEquals]
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    fun structuralEquals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Span
        if (start != other.start) return false
        if (end != other.end) return false
        return true
    }

    /**
     * ⚠︎WARNING: 为了减少ASTNode相等判断的样板代码，本方法忽略Span的[start]和[end]属性
     *
     * 要使用不忽略属性的方法，请使用[structuralHashCode]
     */
    override fun hashCode(): Int {
        return 0
    }

    fun structuralHashCode(): Int {
        return 31 * start + end
    }

    companion object {
        fun between(from: Spanned, to: Spanned): Span {
            return Span(from.span().start, to.span().end)
        }
    }
}