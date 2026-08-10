package mlogix.compiler.core.symbol

import arc.struct.ObjectMap

/**
 * 作用域节点：维护当前作用域内 `名称 → DefId` 的映射，并形成作用域树。
 *
 * 名称解析（Resolver）构建此树并填充绑定；
 * 后续 Pass（如 TypeInferencer）通过 [lookup] 把标识符名称解析为 [DefId]，
 * 再经 [SymbolTable] 查询定义——从而杜绝 `Map<String, Type>` 式的按名查表。
 */
class Scope(val parent: Scope?) {
    private val names = ObjectMap<String, DefId>()

    /**
     * 在当前作用域绑定 名称 → [DefId]。
     * 若名称已在**当前**作用域存在（重复定义），返回 false，由调用方负责报错。
     */
    fun bind(name: String, defId: DefId): Boolean {
        if (names.containsKey(name)) return false
        names.put(name, defId)
        return true
    }

    /**
     * 沿作用域链查找名称对应的 [DefId]；找不到返回 null。
     */
    fun lookup(name: String): DefId? {
        val local = names.get(name)
        if (local != null) return local
        return parent?.lookup(name)
    }

    /**
     * 名称是否在**当前**作用域已绑定（用于重复定义检测）。
     */
    fun containsLocal(name: String): Boolean {
        return names.containsKey(name)
    }

    /**
     * 创建子作用域。
     */
    fun child(): Scope {
        return Scope(this)
    }
}

