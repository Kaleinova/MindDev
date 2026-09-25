package mlogix.compiler.core.symbol

import arc.struct.ObjectMap
import arc.struct.Seq
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.type.TypeOrigin

/**
 * 枚举的变体表：`变体名 → 变体 [DefId]`（挂在枚举类型符号的 [Symbol.values] 上）。
 *
 * 变体名不进入作用域（只能经 `枚举名.变体` 访问），因此这张表就是变体的唯一定义处；
 * 用具名载体而非裸 `ObjectMap`，避免从 `Any?` 取值时的 unchecked cast。
 */
class EnumVariants {
    private val variants = ObjectMap<String, DefId>()

    /** 声明顺序的变体名（`ObjectMap` 本身无序，诊断与穷尽性检查都需要稳定顺序） */
    private val order = Seq<String>(4)

    fun contains(name: String): Boolean = variants.containsKey(name)

    fun get(name: String): DefId? = variants.get(name)

    fun put(name: String, defId: DefId) {
        if (!variants.containsKey(name)) order.add(name)
        variants.put(name, defId)
    }

    /** 全部变体名，按声明顺序 */
    fun names(): Seq<String> {
        val result = Seq<String>(order.size)
        result.addAll(order)
        return result
    }

    /** 变体名列表文本（如 `Red, Green`），用于诊断 */
    fun namesText(): String {
        val builder = StringBuilder()
        for (name in order) {
            if (builder.isNotEmpty()) builder.append(", ")
            builder.append(name)
        }
        return builder.toString()
    }
}

/**
 * 变体载荷信息（挂在变体符号的 [Symbol.values] 上）：
 * 字段名（结构体变体用）与每个字段的类型来源（诊断定位用）。
 *
 * @param names 每个载荷字段的名字；元组变体的字段没有名字（空串）
 * @param origins 每个载荷字段的类型来源树（[TypeOrigin]）：
 *   声明方 label 取它的 `span`，类型不匹配时求解器还会下钻到更精确的子项（如 `Array<Int>` 的 `Int`）
 */
class VariantPayload(val names: Seq<String>, val origins: Seq<TypeOrigin>) {
    val count: Int get() = names.size

    /** 字段名列表文本（如 `width, height`），用于诊断 */
    fun namesText(): String = names.toString(", ")

    /** 第 [index] 个载荷字段的声明位置；越界或缺来源时为 null */
    fun spanOf(index: Int): Span? {
        if (index < 0 || index >= origins.size) return null
        return origins.get(index).span
    }

    /** 第 [index] 个载荷字段的类型来源；越界时为 null */
    fun originAt(index: Int): TypeOrigin? {
        if (index < 0 || index >= origins.size) return null
        return origins.get(index)
    }
}
