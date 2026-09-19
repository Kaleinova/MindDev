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
    /** 类型方案（∀ 多态）。泛型函数声明就绪后由 TypeInferencer 填充 [TypeScheme.typeVars]。 */
    var typeScheme: TypeScheme = TypeScheme(Seq(), type)

    var values = ObjectMap<String?, Any?>()

    companion object {
        /** [values] 中标记"类型参数"符号的键（Resolver 写入，TypeInferencer 读取）。 */
        const val TYPE_PARAM_KEY = "isTypeParam"

        /** [values] 中记录函数声明类型参数数量的键（调用处显式实参数量检查用）。 */
        const val TYPE_PARAM_COUNT_KEY = "typeParamCount"

        /** [values] 中标记"枚举类型"符号的键（Resolver 写入，TypeInferencer 读取）。 */
        const val ENUM_KEY = "isEnum"

        /** [values] 中记录枚举声明类型参数数量的键（Resolver 写入，类型实参数量检查用）。 */
        const val ENUM_TYPE_PARAM_COUNT_KEY = "enumTypeParamCount"

        /** [values] 中记录变体表 [EnumVariants] 的键（Resolver 写入，`枚举名.变体` 查找用）。 */
        const val ENUM_VARIANTS_KEY = "enumVariants"

        /** [values] 中标记"枚举变体"符号的键（Resolver 写入，TypeInferencer 读取）。 */
        const val ENUM_VARIANT_KEY = "isEnumVariant"

        /** [values] 中记录变体载荷信息 [VariantPayload] 的键（TypeInferencer 写入，诊断用）。 */
        const val VARIANT_PAYLOAD_KEY = "variantPayload"
    }
}
