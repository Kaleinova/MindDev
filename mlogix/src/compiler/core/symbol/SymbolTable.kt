package mlogix.compiler.core.symbol

import arc.struct.ObjectMap
import arc.struct.Seq
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.type.Type

/**
 * 符号表：以 [DefId] 为中心的定义仓库（对齐 rustc 的 DefId 表）。
 *
 * 与旧式 `Map<String, Type>` 的关键区别：
 * - 每个定义拥有稳定的 [DefId]，名称可以遮蔽/重载而不互相污染；
 * - Resolver 在此登记定义；TypeInferencer 只按 [DefId] 查询/写回类型。
 */
class SymbolTable {
    private var nextId: Int = 0
    private val symbols = ObjectMap<DefId, Symbol>()

    /**
     * 登记一个新定义，分配 [DefId]。
     */
    fun declare(name: String, type: Type, span: Span): Symbol {
        val symbol = Symbol(DefId(nextId++), name, type, span)
        symbols.put(symbol.id, symbol)
        return symbol
    }

    /**
     * 按 [DefId] 查询定义；不存在返回 null。
     */
    fun get(defId: DefId): Symbol? {
        return symbols.get(defId)
    }

    /**
     * 全部定义（用于求解后统一写回类型）。
     */
    fun all(): Seq<Symbol> {
        val seq = Seq<Symbol>()
        symbols.forEach { entry: ObjectMap.Entry<DefId, Symbol> ->
            seq.add(entry.value)
        }
        return seq
    }
}

