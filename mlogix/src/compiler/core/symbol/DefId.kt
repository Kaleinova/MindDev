package mlogix.compiler.core.symbol

/**
 * 定义句柄（DefId）：全局唯一的定义标识。
 *
 * 对齐 rustc 的 DefId/HirId 设计：AST/IR 中的名称一律解析为 [DefId]，
 * 具体信息（类型、作用域）通过 [SymbolTable] 按 [DefId] 查询，
 * 而不是用 `Map<String, Type>` 按名称查询。这彻底解耦了"名称解析"与"类型推断"。
 */
data class DefId(val id: Int)

