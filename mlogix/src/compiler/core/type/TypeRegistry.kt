package mlogix.compiler.core.type

import arc.struct.ObjectMap

/**
 * 具名类型的注册表 / 轻量 interner。
 *
 * 现状：只对 [Type.Con]（具名构造类型）做 intern——同名的 Con 返回同一实例，
 * 使引用相等（`===`）对内置/用户类型安全。
 *
 * 远期扩展：
 * - 对 [Type.Func] / [Type.Arr] / [Type.TupleType] 做结构 hash-consing
 *   （以子类型为键缓存，保证同构类型是同一实例）；
 * - 承载用户定义类型（类/结构体）的注册与查询。
 */
class TypeRegistry {
    private val types = ObjectMap<String, Type.Con>()

    init {
        register(BuiltinType.Int)
        register(BuiltinType.Str)
    }

    fun register(type: Type.Con) {
        types.put(type.name, type)
    }

    /**
     * 按名获取或创建 [Type.Con]（同名返回同一实例）。
     */
    fun con(name: String): Type.Con {
        return types.get(name) ?: Type.Con(name).also { types.put(name, it) }
    }

    fun get(name: String?): Type.Con? {
        return types.get(name)
    }
}