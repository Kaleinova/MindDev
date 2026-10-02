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

        /** [values] 中标记"结构体类型"符号的键（Resolver 写入，TypeInferencer 读取）。 */
        const val STRUCT_KEY = "isStruct"

        /** [values] 中记录结构体声明类型参数数量的键（Resolver 写入，类型实参数量检查用）。 */
        const val STRUCT_TYPE_PARAM_COUNT_KEY = "structTypeParamCount"

        /** [values] 中记录字段表 [StructFields] 的键（Resolver 写入，字段访问查找用）。 */
        const val STRUCT_FIELDS_KEY = "structFields"

        /** [values] 中记录方法表 [StructMethods] 的键（Resolver 写入，方法调用查找用）。 */
        const val STRUCT_METHODS_KEY = "structMethods"

        /**
         * [values] 中记录字段声明表 [StructFieldTable] 的键
         * （Resolver 写入字段名/DefId/位置/来源，TypeInferencer 写入字段类型）。
         */
        const val STRUCT_FIELD_TABLE_KEY = "structFieldTable"

        /** [values] 中标记"结构体字段"符号的键（Resolver 写入，成员访问判定用）。 */
        const val STRUCT_FIELD_KEY = "isStructField"

        /**
         * [values] 中记录**结构体字段类型**的键（TypeInferencer 写入，按声明顺序）：
         * 字段符号自身也挂着类型，但构造器检查要按顺序取一整个列表，故在结构体符号上再存一份。
         */
        const val STRUCT_FIELD_TYPES_KEY = "structFieldTypes"

        /** [values] 中记录**结构体字段默认值**的键（TypeInferencer 写入，按声明顺序；无默认值的位为 null）。 */
        const val STRUCT_FIELD_DEFAULTS_KEY = "structFieldDefaults"

        /** [values] 中标记"结构体方法"符号的键（Resolver 写入，成员访问判定用）。 */
        const val STRUCT_METHOD_KEY = "isStructMethod"

        /** [values] 中记录方法**所属结构体符号**的键（Resolver 写入，方法调用时取签名与成员表）。 */
        const val STRUCT_METHOD_OWNER_KEY = "structMethodOwner"

        /**
         * [values] 中标记「结构体符号的类型方案是**构造器**」的键（TypeInferencer 写入）。
         * 值位置读结构体名（`set f = Point`）时据此产出构造器函数类型，而不是结构体类型本身。
         */
        const val STRUCT_CONSTRUCTOR_KEY = "structConstructor"
    }
}
