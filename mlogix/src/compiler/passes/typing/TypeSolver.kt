package mlogix.compiler.passes.typing

import arc.struct.IntMap
import arc.struct.ObjectMap
import arc.struct.Seq
import mlogix.compiler.core.SourceMap.SourceFile
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.type.Type
import mlogix.compiler.core.type.TypeVisitor
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.diagnostic.DiagHandler

/**
 * Union-Find（并查集）求解器：惰性求解 Equal 约束。
 *
 * 专业化设计：
 * - **[Type.Var] 用 Int 索引**，并查集 parent 用 [IntMap]（原始 int 键），
 *   避免 String id 的分配与哈希开销；
 * - **Occurs check 是结构化遍历**（[TypeVisitor] + visited 集合），
 *   不再用 `toString().contains()` 字符串匹配（旧实现会把 `t1` 与 `t10` 误判为递归）；
 * - **[Type.Error] / [Type.Unknown] 静默通过**：错误类型抑制级联错误，
 *   未定类型不与任何具体类型冲突。
 */
class TypeSolver(private val problems: DiagHandler, private val sourceFile: SourceFile) {
    private val parent = IntMap<Int>()
    private val rootType = IntMap<Type>()
    private var counter = 0

    fun freshVar(): Type.Var {
        return Type.Var(counter++)
    }

    private fun find(v: Int): Int {
        val p = parent.get(v) ?: return v
        if (p == v) return v
        val r = find(p)
        parent.put(v, r)
        return r
    }

    private fun unionVars(v1: Int, v2: Int) {
        val r1 = find(v1)
        val r2 = find(v2)
        if (r1 == r2) return
        // attach r2 -> r1
        parent.put(r2, r1)
        val t1 = rootType.get(r1)
        if (t1 == null) rootType.put(r1, rootType.get(r2))
        rootType.remove(r2)
    }

    fun bind(varIndex: Int, ty: Type) {
        rootType.put(find(varIndex), ty)
    }

    fun read(t: Type): Type {
        return walk(t)
    }

    private fun walk(t: Type): Type {
        return when (t) {
            is Type.Var -> {
                val root = find(t.index)
                val rt = rootType.get(root)
                if (rt == null) Type.Var(root) else walk(rt)
            }

            is Type.Func -> {
                val params = t.params.map { walk(it) }
                Type.Func(params, walk(t.result))
            }

            is Type.Arr -> Type.Arr(walk(t.element))

            is Type.TupleType -> {
                val elements = t.elements.map { walk(it) }
                Type.TupleType(elements)
            }

            is Type.Con, Type.Unknown, Type.Error -> t
        }
    }

    /** Normalize a type by walking and replacing any type variables with their solved types. */
    fun normalize(t: Type): Type {
        return walk(t)
    }

    fun solveEqualities(constraints: Seq<Constraint>) {
        for (c in constraints) {
            if (c is Constraint.Equal) {
                unify(c.t1, c.t2, c.pos, c.declPos)
            }
        }
    }

    private fun unify(a: Type, b: Type, pos: Span?, declPos: Span?) {
        val t1 = walk(a)
        val t2 = walk(b)
        when {
            t1 is Type.Var && t2 is Type.Var -> unionVars(t1.index, t2.index)

            t1 is Type.Var -> {
                if (occurs(t1, t2)) {
                    reportOccurs(t1, pos, declPos)
                    return
                }
                bind(t1.index, t2)
            }

            t2 is Type.Var -> {
                if (occurs(t2, t1)) {
                    reportOccurs(t2, pos, declPos)
                    return
                }
                bind(t2.index, t1)
            }

            t1 is Type.Func && t2 is Type.Func -> {
                if (t1.params.size != t2.params.size) {
                    report(
                        "函数参数数量不匹配: 声明 ${t2.params.size} 个形参, 传入 ${t1.params.size} 个实参",
                        pos,
                        declPos
                    )
                    return
                }
                for ((i, element) in t1.params.withIndex()) {
                    unify(element, t2.params.get(i), pos, declPos)
                }
                unify(t1.result, t2.result, pos, declPos)
            }

            t1 is Type.Arr && t2 is Type.Arr -> unify(t1.element, t2.element, pos, declPos)

            t1 is Type.TupleType && t2 is Type.TupleType -> {
                if (t1.elements.size != t2.elements.size) {
                    report("元组元素数量不匹配: ${t1.elements.size} != ${t2.elements.size}", pos, declPos)
                    return
                }
                for ((i, element) in t1.elements.withIndex()) {
                    unify(element, t2.elements.get(i), pos, declPos)
                }
            }

            // 错误类型 / 未定类型：静默通过（抑制级联错误，允许继续推断）
            t1 is Type.Error || t2 is Type.Error || t1 is Type.Unknown || t2 is Type.Unknown -> Unit

            t1 != t2 -> reportMismatch(t1, t2, pos, declPos)
        }
    }

    /**
     * 结构化 occurs check：检查类型变量 [varT] 是否出现在 [t] 中（避免无限递归绑定）。
     *
     * 用 visited 记录已展开的并查集根，防止通过已绑定变量产生环导致死循环。
     */
    private fun occurs(varT: Type.Var, t: Type): Boolean {
        val visited = ObjectMap<Int, Boolean>()
        val visitor = object : TypeVisitor {
            var found = false

            override fun visit(type: Type) {
                if (found) return
                when (type) {
                    is Type.Var -> {
                        if (type.index == varT.index) {
                            found = true
                            return
                        }
                        // 变量已绑定到具体类型时，继续展开
                        val root = find(type.index)
                        val rt = rootType.get(root) ?: return
                        if (visited.containsKey(root)) return
                        visited.put(root, true)
                        visit(rt)
                    }

                    is Type.Con, Type.Unknown, Type.Error -> Unit
                    else -> super.visit(type)
                }
            }
        }
        visitor.visit(t)
        return visitor.found
    }

    private fun reportOccurs(varT: Type.Var, pos: Span?, declPos: Span?) {
        report("Occurs check failed for ${varT.index}", pos, declPos)
    }

    /**
     * 类型不匹配报错：**同一个 Diagnostic** 下打印双方类型。
     *
     * - 消息正文列出双方：`期望 <声明方类型>, 实际 <使用方类型>`；
     * - [Diagnostic.label] 标记**使用方**（[pos]，实际类型来源处）；
     * - [Diagnostic.label] 标记**声明方**（[declPos]，期望类型声明处，可能为 null）。
     */
    private fun reportMismatch(t1: Type, t2: Type, pos: Span?, declPos: Span?) {
        val e = Diagnostic.SemanticDiag(
            "类型不匹配: 期望 ${t2.pretty()}, 实际 ${t1.pretty()}",
            Diagnostic.DiagLevel.ERROR,
        )
        problems.addError(e)
        e.label(pos ?: Span(sourceFile.index, 0, 0), "实际类型: ${t1.pretty()}")
        if (declPos != null) {
            e.label(declPos, "期望类型: ${t2.pretty()}")
        }
    }

    private fun report(text: String, pos: Span?, declPos: Span?) {
        val e = Diagnostic.SemanticDiag(text, Diagnostic.DiagLevel.ERROR)
        problems.addError(e)
        e.label(pos ?: Span(sourceFile.index, 0, 0), "使用方")
        if (declPos != null) {
            e.label(declPos, "声明方")
        }
    }
}

