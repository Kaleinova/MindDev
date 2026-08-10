package mlogix.compiler.core.type

import arc.struct.IntSet
import arc.struct.ObjectMap
import arc.struct.Seq

/**
 * 类型方案（Type Scheme），用于表示带"全称量化"的多态类型。
 *
 * 在 HM（Hindley-Milner）类型系统中，
 * 一个泛型函数（如 `fn id<T>(x: T) -> T`）的类型不能简单地用普通类型表示，
 * 而必须明确记录"哪些类型变量是被量化的（属于这个函数自己的）"。
 *
 * 本类本质上对应数学形式：**∀ α₁ α₂ ... αₙ. Body**
 * - 其中 `∀` 表示"对于所有类型"；
 * - `α₁..αₙ` 是泛型参数（如 `<T, E>`）；
 * - `Body` 是去掉了量词后的类型结构（如 `fn(T) -> T`）。
 *
 * @property typeVars 被 `∀` 绑定的类型变量列表（如 `[T, E]`）。这些变量在 `body` 中会被引用。
 * @property body 主体类型，即去掉了 `∀` 前缀后的类型表达式。
 *                  注意：`body` 中出现的自由变量（指未在 `typeVars` 中声明的变量）
 *                  必须在更大的上下文（如外层函数或结构体）中定义。
 *
 * @see instantiate 用于在调用泛型函数时生成具体类型
 * @see generalize 用于从普通类型推导出泛型类型方案
 */
class TypeScheme(
    val typeVars: Seq<Type.Var>,
    val body: Type,
) {

    /**
     * 实例化（Instantiation）：将当前的泛型类型方案转换为一个**具体的**、可使用的类型。
     *
     * 这是泛型多态最核心的操作。
     * 每次调用泛型函数（如调用 `foo<Int>(42)`），编译器都必须执行此操作。
     *
     * **为什么需要这个方法？**
     * 因为在类型方案中，`typeVars` 是共享的（属于函数定义本身）。
     * 如果我们在函数被调用时不进行处理，所有调用点会共用同一组类型变量，导致类型冲突。
     * 例如 `set a = id(42); set b = id("hello")`，如果两次调用共用同一个变量 `T`，
     * 那么第一次将 `T` 固定为 `i32` 后，第二次传入 `String` 就会报错。
     *
     * **解决方式：**
     * 本方法会为 `typeVars` 中的**每一个**量化变量生成一个**全新的、独一无二的**类型变量，
     * 然后将 `body` 中的所有旧变量替换为新变量。这样，每次调用都会获得完全独立的一套类型参数。
     *
     * @param freshVar 一个高阶函数（工厂），用于生成一个新的类型变量（`Type.Var`）。
     *                 通常由 `TypeSolver` 提供，确保每次生成的变量索引（`index`）是全局唯一的。
     *                 之所以设计为函数参数而非直接 `new`，是为了将"如何生成变量"的逻辑与"如何替换"的逻辑解耦。
     *
     * @return 替换后的具体类型（`Type`）。
     *         此时返回的类型中不再包含量化变量，所有占位符都已被替换为全新的具体变量，
     *         可以立即与参数类型进行统一（Unification）。
     *
     * @see substitute 本方法依赖的底层替换逻辑
     */
    fun instantiate(freshVar: () -> Type.Var): Type {
        val subsTable = ObjectMap<Int, Type.Var>()
        for ((index) in typeVars) subsTable.put(index, freshVar())
        return substitute(body, subsTable)
    }

    /**
     * 泛化（Generalization）：将当前类型（`body`）中**未受约束**的自由类型变量提升为量化变量。
     *
     * 这是 set 多态（Set-Polymorphism）的基础。
     * 通常在处理 set 绑定（如`set x = ...` 或函数定义）时，
     * 如果推断出的类型中包含一些尚未确定的类型变量，且这些变量没有受到外部环境的限制，
     * 我们就可以把它们"装进盒子"（量化），形成一个泛型类型方案。
     *
     * **必须理解 `envFreeVars`（环境自由变量）的作用：**
     * - 假设我们有嵌套作用域：外层定义了一个泛型结构体 `struct Wrapper<T> { ... }`，
     *   内层定义了一个闭包 `x -> x`。
     * - 内层闭包的 `freeTypeVars()` 可能包含外层的 `T`。
     * - 但是，`T` 是属于外层结构体的，内层闭包**不能**把外层的 `T` 抢过来作为自己的泛型参数（否则类型会混乱）。
     * - 因此，`generalize` 会检查 `envFreeVars`，**跳过**那些已经在环境中被占用的变量，只量化"真正自由"的本地变量。
     *
     * **操作步骤：**
     * 1. 调用 `freeTypeVars()` 获取 `body` 中出现的所有类型变量（去重）。
     * 2. 过滤掉那些索引存在于 `envFreeVars` 中的变量（即外部环境已经声明的）。
     * 3. 将剩余的变量作为新的 `typeVars`，构造一个新的 `TypeScheme` 实例返回。
     *
     * @param envFreeVars 一个整数集合（`IntSet`），包含当前类型环境（`TypeEnv`）中
     *                    所有已被占用的类型变量的索引。
     *                    这些变量虽然出现在 `body` 中，但属于外部上下文，禁止在此处被量化。
     *
     * @return 一个新的 `TypeScheme` 实例。其 `typeVars` 包含刚刚量化的新变量，
     *         而 `body` 保持不变（因为变量本身就是指向 `body` 内部的引用）。
     *
     * @see freeTypeVars 用于收集 `body` 中的所有候选变量
     */
    fun generalize(envFreeVars: IntSet): TypeScheme {
        val quantified = Seq<Type.Var>()
        val seen = ObjectMap<Int, Boolean>()
        for (v in freeTypeVars()) {
            if (!envFreeVars.contains(v.index) && !seen.containsKey(v.index)) {
                quantified.add(v)
                seen.put(v.index, true)
            }
        }
        return TypeScheme(quantified, body)
    }


    /**
     * 收集并返回当前 `body` 类型中出现的**所有**自由类型变量，并按索引去重。
     *
     * 这是一个辅助工具方法，主要用于支持 `generalize` 方法。
     * 由于类型 `body` 是一个树形结构（可能包含函数类型、数组类型、元组类型等子节点），
     * 我们必须递归遍历整棵树，找出所有 `Type.Var` 叶子节点。
     *
     * **注意：** 这里的"自由"是相对于"整个 `body`"而言的。因为 `body` 本身并没有量化前缀，
     * 所以只要出现在 `body` 中的变量，在这个上下文中都算作"自由变量"。
     * 至于这些变量是否真的能被量化（即不在 `envFreeVars` 中），由 `generalize` 决定。
     *
     * **实现细节：**
     * 利用访问者模式（`TypeVisitor`）遍历类型树。虽然 `Type` 有多种子类（`Var`、`Con`、`Func` 等），
     * 但在此场景下我们只关心 `visitVar` 事件。其他节点（如 `Func`）会递归地访问其子节点（参数类型和返回类型）。
     *
     * @return 包含所有不同 `Type.Var` 实例的序列（`Seq`）。
     *         返回的变量顺序不保证稳定，但每个变量索引（`index`）仅出现一次。
     */
    fun freeTypeVars(): Seq<Type.Var> {
        val result = Seq<Type.Var>()
        val seen = ObjectMap<Int, Boolean>()
        body.accept(object : TypeVisitor {
            override fun visitVar(type: Type.Var) {
                if (!seen.containsKey(type.index)) {
                    seen.put(type.index, true)
                    result.add(type)
                }
            }
        })
        return result
    }


    /**
     * 私有工具方法：递归地对给定的类型 `t` 执行替换操作。
     *
     * 这是 `instantiate` 的底层实现，负责遍历类型结构，将旧变量替换为新变量。
     *
     * **为什么需要递归？**
     * 类型不是扁平的。例如 `Type.Func` 包含参数列表（`params`）和返回值（`result`），
     * 这些子节点可能又包含其他子节点（如 `Type.Arr` 包含 `element`）。
     * 我们必须深入每一种类型构造器，确保任何深层的变量都被替换干净。
     *
     * **处理各种类型分支：**
     * - **`Type.Var`**：查找映射表 `subst`。如果找到了该变量的索引，返回对应的新变量；否则返回原变量（即没有命中替换的保留）。
     * - **`Type.Con`**：具体类型（如 `Int`、`String`），没有任何变量，直接返回自身。
     * - **`Type.Func`**：函数类型（如 `(A, B) -> C`）。递归替换所有参数类型和结果类型，构造新的 `Func`。
     * - **`Type.Arr`**：数组类型（如 `[]T`）。递归替换元素类型。
     * - **`Type.TupleType`**：元组类型（如 `(A, B)`）。递归替换所有元素。
     * - **`Type.Unknown` / `Type.Error`**：特殊占位符（用于尚未推断或报错的情况），保持不变。
     *
     * @param type 待替换的原始类型
     * @param subsTable 替换映射表，键为 `Int`（旧类型变量的索引），值为 `Type.Var`（新类型变量）
     * @return 完全替换后的新类型（如果替换映射未命中，则返回原类型对象的引用以节省内存）
     */
    private fun substitute(type: Type, subsTable: ObjectMap<Int, Type.Var>): Type {
        return when (type) {
            is Type.Var -> subsTable.get(type.index) ?: type
            is Type.Con -> type

            is Type.Func -> {
                val params = type.params.map { substitute(it, subsTable) }
                Type.Func(params, substitute(type.result, subsTable))
            }

            is Type.Arr -> Type.Arr(substitute(type.element, subsTable))

            is Type.TupleType -> {
                val elements = type.elements.map { substitute(it, subsTable) }
                Type.TupleType(elements)
            }

            Type.Unknown, Type.Error -> type
        }
    }
}
