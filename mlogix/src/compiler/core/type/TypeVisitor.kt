package mlogix.compiler.core.type

/**
 * 类型遍历器（对齐 rustc 的 `TypeVisitable` / GHC 的 fold 基础设施）。
 *
 * 默认 [visit] 会递归访问子节点；子类只需覆写感兴趣的叶子方法（如 [visitVar]），
 * 即可在遍历任意类型时获得回调——Occurs check、自由变量收集、泛型替换、类型打印
 * 都通过它实现，避免每个 Pass 手写一份 walk。
 */
interface TypeVisitor {

    /**
     * 访问一个类型节点。
     *
     * 默认实现：递归访问子节点（Func 的形参与返回、Arr 的元素、TupleType 的元素）。
     * Unknown / Error 视为叶子（无子节点）。
     * 注意：若子类覆写本方法，必须自行决定是否继续递归。
     */
    fun visit(type: Type) {
        when (type) {
            is Type.Var -> visitVar(type)
            is Type.Con -> visitCon(type)

            is Type.Func -> {
                for (p in type.params) visit(p)
                visit(type.result)
            }

            is Type.Arr -> visit(type.element)

            is Type.TupleType -> {
                for (e in type.elements) visit(e)
            }

            Type.Unknown, Type.Error -> Unit
        }
    }

    fun visitVar(type: Type.Var) {}
    fun visitCon(type: Type.Con) {}
}
