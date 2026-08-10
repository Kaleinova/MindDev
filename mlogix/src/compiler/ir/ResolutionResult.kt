package mlogix.compiler.ir

import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.symbol.Scope
import mlogix.compiler.core.symbol.SymbolTable

/**
 * Resolver 的输出 IR：作用域树 + 符号表（DefId 中心）。
 *
 * 这是解析（PARSE）之后、类型推断（TYPE_INFERENCE）之前的中间表示：
 * - [ast]：仍为原始 AST，但其中 `Expr.Identifier`/`Stmt.FnStmt` 已由 Resolver
 *   填上 `defId`（指向 [symbolTable] 中的定义）；
 * - [rootScope]：全局作用域（内置类型预置），可沿 [Scope.parent] 递归查找名称 → DefId；
 * - [symbolTable]：按 [DefId] 查询定义的仓库，类型推断只读写这里，不再按名称查表。
 */
class ResolutionResult(
    val ast: Stmt,
    val rootScope: Scope,
    val symbolTable: SymbolTable,
)

