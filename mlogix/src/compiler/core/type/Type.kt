package mlogix.compiler.core.type

import arc.struct.Seq

/**
 * 类型：不可变的代数结构（对齐 rustc 的 `TyKind` / GHC 的 `Type`）。
 *
 * 设计原则：
 * - **sealed + data class**：结构相等与 hashCode 由编译期生成，`when` 穷尽匹配，
 *   不再靠 String name 或引用相等（`===`）判类型。
 * - **不可变**：字段/方法等附属信息不挂在类型节点上（见 [TypeRegistry] / 未来的类型环境），
 *   类型本身只是纯数据 DAG，可安全缓存、可被多个 Pass 共享。
 * - **[Var] 以 Int 为索引**：指向求解器并查集的槽位，避免 String 分配与字符串哈希。
 * - **[Unknown] 与 [Error] 严格分离**：
 *   [Unknown] = "尚未约束，允许继续推断"；[Error] = "诊断已报告，后续约束静默通过"（抑制级联错误）。
 */
sealed class Type {

    /** 具名构造类型（内置类型与未来的用户定义类型） */
    data class Con(val name: String) : Type()

    /** 类型变量：index 指向求解器并查集的槽位 */
    data class Var(val index: Int) : Type()

    /** 函数类型 `(params) -> result` */
    data class Func(val params: Seq<Type>, val result: Type) : Type()

    /** 数组类型 `Array<element>`（携带元素类型，不再丢失泛型参数） */
    data class Arr(val element: Type) : Type()

    /** 元组类型 `(T1, T2, ...)` */
    data class TupleType(val elements: Seq<Type>) : Type()

    /** 未定类型：尚未被约束（允许再次推断） */
    data object Unknown : Type()

    /** 错误类型：诊断已报告，抑制级联错误 */
    data object Error : Type()

    /**
     * 以 [TypeVisitor] 遍历此类型（默认递归访问子节点）。
     */
    fun accept(visitor: TypeVisitor) {
        visitor.visit(this)
    }

    /**
     * 友好打印类型（用于诊断消息；区别于 data class 自动生成的 `toString`）。
     */
    fun pretty(): String = when (this) {
        is Con -> name
        is Var -> "Var($index)"
        is Func -> "(${params.joinToString(", ") { it.pretty() }}) -> ${result.pretty()}"
        is Arr -> "Array<${element.pretty()}>"
        is TupleType -> "(${elements.joinToString(", ") { it.pretty() }})"
        Unknown -> "Unknown"
        Error -> "Error"
    }
}

