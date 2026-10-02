package mlogix.compiler.core.symbol

import arc.struct.ObjectMap
import arc.struct.Seq
import mlogix.compiler.ast.Expr
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.type.Type
import mlogix.compiler.core.type.TypeOrigin

/**
 * 结构体的字段表：`字段名 → 字段 [DefId]`（挂在结构体类型符号的 [Symbol.values] 上）。
 *
 * 与 [EnumVariants] 同构：字段名**不进入外层作用域**（只能经 `实例.字段名` 访问，
 * 方法体内则由 Resolver 预绑定成隐式接收者 `self.字段名`），因此这张表就是字段的唯一定义处。
 * 用具名载体而非裸 `ObjectMap`，避免从 `Any?` 取值时的 unchecked cast。
 */
class StructFields {
    private val fields = ObjectMap<String, DefId>()

    /** 声明顺序的字段名（`ObjectMap` 本身无序，诊断需要稳定顺序） */
    private val order = Seq<String>(4)

    fun contains(name: String): Boolean = fields.containsKey(name)

    fun get(name: String): DefId? = fields.get(name)

    fun put(name: String, defId: DefId) {
        if (!fields.containsKey(name)) order.add(name)
        fields.put(name, defId)
    }

    /** 全部字段名，按声明顺序 */
    fun names(): Seq<String> {
        val result = Seq<String>(order.size)
        result.addAll(order)
        return result
    }

    /** 字段名列表文本（如 `x, y`），用于诊断 */
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
 * 结构体的方法表：`方法名 → 方法符号 [DefId]`（挂在结构体类型符号的 [Symbol.values] 上）。
 *
 * 与 [StructFields] 分开存：字段是数据位（有类型、参与构造），方法是可调用成员
 * （类型是 `(self, ...) -> 结果`），两者在构造与调用处要走不同的检查路径。
 */
class StructMethods {
    private val methods = ObjectMap<String, DefId>()
    private val order = Seq<String>(4)

    fun contains(name: String): Boolean = methods.containsKey(name)

    fun get(name: String): DefId? = methods.get(name)

    fun put(name: String, defId: DefId) {
        if (!methods.containsKey(name)) order.add(name)
        methods.put(name, defId)
    }

    /** 全部方法名，按声明顺序 */
    fun names(): Seq<String> {
        val result = Seq<String>(order.size)
        result.addAll(order)
        return result
    }

    /** 方法名列表文本（如 `dist, len`），用于诊断 */
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
 * 结构体字段的**推断结果**表（挂在结构体符号的 [Symbol.values] 上，按声明顺序）：
 * 每个字段的类型、默认值表达式与类型来源。
 *
 * 用具名载体而不是裸 `Seq<Type>`：`Any?` 里取出泛型 `Seq` 会触发 unchecked cast，
 * 而且「字段类型」「字段默认值」「字段来源」必须按下标严格对齐，分开存容易错位。
 */
class StructFieldTypes(
    val types: Seq<Type>,
    val defaults: Seq<Expr?>,
) {
    /** 字段个数（声明顺序）；[types] 与 [defaults] 等长 */
    val count: Int get() = types.size

    /** 第 [index] 个字段的类型；越界时返回 [Type.Error] */
    fun typeAt(index: Int): Type {
        if (index < 0 || index >= types.size) return Type.Error
        return types.get(index)
    }

    /** 第 [index] 个字段的默认值；无默认值或越界时为 null */
    fun defaultAt(index: Int): Expr? {
        if (index < 0 || index >= defaults.size) return null
        return defaults.get(index)
    }
}

/**
 * 结构体字段的**声明信息**（挂在结构体符号的 [Symbol.values] 上，按声明顺序保存）：
 * 每个字段的名字、[DefId]、声明位置与类型来源。
 *
 * [origins] / [spans] / [defIds] 与 [names] 下标一一对应：字段类型不匹配时，声明方 label
 * 用 [origins] 里对应字段的来源（可下钻到 `Array<Int>` 的 `Int`），字段本身的位置用 [spans]。
 * 字段类型不在这里存——它随构造与成员访问逐点计算（见 [StructFieldTypes] 与
 * `TypeInferencer` 的成员代入逻辑）。
 */
class StructFieldTable(
    val names: Seq<String>,
    val defIds: Seq<DefId>,
    val spans: Seq<Span>,
    val origins: Seq<TypeOrigin>,
) {
    val count: Int get() = names.size

    /** 字段名列表文本（如 `x, y`），用于诊断 */
    fun namesText(): String = names.toString(", ")

    /** 名字对应字段的下标；没有该字段时为 -1 */
    fun indexOf(name: String): Int {
        for ((i, candidate) in names.withIndex()) {
            if (candidate == name) return i
        }
        return -1
    }

    /** 第 [index] 个字段的声明位置；越界时为 null */
    fun spanOf(index: Int): Span? {
        if (index < 0 || index >= spans.size) return null
        return spans.get(index)
    }

    /** 第 [index] 个字段的类型来源；越界时为 null */
    fun originAt(index: Int): TypeOrigin? {
        if (index < 0 || index >= origins.size) return null
        return origins.get(index)
    }
}
