package mlogix.compiler.core.symbol

import arc.struct.ObjectMap
import arc.struct.Seq
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.type.Type
import mlogix.compiler.core.type.TypeScheme

/**
 * 定义记录：以 [DefId] 为句柄，存于 [SymbolTable]。
 *
 * 名称解析（Resolver）与类型推断（TypeInferencer）共享此记录：
 * - Resolver 负责创建记录、绑定 名称 → [DefId]、挂载 [typeScheme]；
 * - TypeInferencer 只通过 [id] 读写 [type]，绝不按名称查表。
 *
 * [values] 是推断过程的临时中转存储（"inferred" 类型变量 / "final" 求解结果），
 * 后续接入正式 IR 后迁移为 TypedHir 的字段。
 */
class Symbol(
    val id: DefId,
    val name: String,
    var type: Type,
    /** 符号定义处的位置 */
    val span: Span,
) {
    /** 类型方案（∀ 多态）。泛型声明语法就绪后由 Resolver 填充 [TypeScheme.typeVars]。 */
    var typeScheme: TypeScheme = TypeScheme(Seq(), type)

    var values = ObjectMap<String?, Any?>()
}
