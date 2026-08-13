package mlogix.compiler.core.span


class Span : Spanned {
    companion object {
        const val INDEX_BITS = 18
        const val START_BITS = 25
        const val LEN_BITS = 21

        fun between(index: Int, start: Int, end: Int): Span {
            return Span(index, start, end - start)
        }

        fun between(from: Spanned, to: Spanned): Span {
            require(from.span().index() == to.span().index()) {
                "不能使用index不同的Span: Span.between(${from.span().toStructuralString()},${
                    to.span().toStructuralString()
                })"
            }
            return between(
                from.span().index(),
                from.span().start(),
                to.span().end(),
            )
        }
    }
    private val bits: Long

    constructor(index: Int, start: Int, len: Int) {
        bits = (index.toLong() shl (START_BITS + LEN_BITS)) or
                (start.toLong() shl LEN_BITS) or
                len.toLong()
    }

    override fun span(): Span = this

    /** 所在文件的索引 */
    fun index(): Int {
        return (bits ushr (START_BITS + LEN_BITS)).toInt()
    }

    /** 在文件中左端的字符位置 */
    fun start(): Int {
        return ((bits ushr LEN_BITS) and 0x1FF_FFFF).toInt()
    }

    /** 长度 */
    fun len(): Int {
        return (bits and 0x1F_FFFF).toInt()
    }

    /** 在文件中右端的字符位置 */
    fun end(): Int {
        return start() + len()
    }

    /**
     * ⚠︎WARNING: 为了减少ASTNode相等判断的样板代码，本方法忽略Span的[bits],[index],[start]和[len]属性
     *
     * 要使用不忽略属性的方法，请使用[toStructuralString]
     */
    override fun toString(): String = "Span"

    fun toStructuralString(): String = "Span{${index()},${start()},${len()}}"

    /**
     * ⚠︎WARNING: 为了减少ASTNode相等判断的样板代码，本方法忽略Span的[bits],[index],[start]和[len]属性
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
        return bits == other.bits
    }

    /**
     * ⚠︎WARNING: 为了减少ASTNode相等判断的样板代码，本方法忽略Span的[bits],[index],[start]和[len]属性
     *
     * 要使用不忽略属性的方法，请使用[structuralHashCode]
     */
    override fun hashCode(): Int {
        return 0
    }

    fun structuralHashCode(): Int {
        return bits.hashCode()
    }

}