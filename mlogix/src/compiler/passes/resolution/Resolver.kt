package mlogix.compiler.passes.resolution

import mlogix.compiler.core.symbol.DefId
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.SourceMapManager.SourceMap
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.symbol.Scope
import mlogix.compiler.core.symbol.Symbol
import mlogix.compiler.core.symbol.SymbolTable
import mlogix.compiler.core.type.BuiltinType
import mlogix.compiler.core.type.Type
import mlogix.compiler.core.type.TypeScheme
import mlogix.compiler.diagnostic.Problem
import mlogix.compiler.diagnostic.Problem.SemanticProblem
import mlogix.compiler.diagnostic.ProblemCollector
import mlogix.compiler.ir.ResolutionResult
import arc.struct.Seq

/**
 * 名称解析 Pass：构建作用域树，登记定义（分配 [DefId]），挂载 TypeScheme。
 *
 * 职责边界：
 * - **只做名字与作用域**：声明符号、绑定 `名称 → DefId`、把 AST 中每个 `Identifier`
 *   的 [Expr.Identifier.defId] 填好；不做任何类型计算。
 * - 输出 [ResolutionResult]（作用域树 + 符号表），类型推断据此按 `DefId` 查表，
 *   不再出现 `Map<String, Type>` 式的按名查询。
 *
 * 内置类型（Int/Num/Str/Bool/Null/Array/Fn/Ref）预置进全局作用域（prelude），
 * 因此类型注解里的名字也能被解析。
 */
class Resolver(private val problems: ProblemCollector) {
    private lateinit var sourceMap: SourceMap
    private lateinit var symbolTable: SymbolTable
    private lateinit var rootScope: Scope

    /**
     * 一个文件 一次调用
     */
    fun resolve(ast: Stmt, sourceMap: SourceMap): ResolutionResult {
        this.sourceMap = sourceMap
        this.symbolTable = SymbolTable()
        this.rootScope = Scope(null)

        registerBuiltins(rootScope)

        resolveStmt(ast, rootScope)

        return ResolutionResult(ast, rootScope, symbolTable)
    }

    // ========== 内置类型预置（prelude） ==========
    private fun registerBuiltins(scope: Scope) {
        val builtins = Seq.with(
            BuiltinType.Num, BuiltinType.Int, BuiltinType.Str, BuiltinType.Bool,
            BuiltinType.Null, BuiltinType.Array, BuiltinType.Fn, BuiltinType.Ref,
        )
        for (type in builtins) {
            val symbol = symbolTable.declare(type.name, type, Span(sourceMap.index, 0, 0))
            scope.bind(type.name, symbol.id)
        }
    }

    // ========== 语句解析 ==========
    private fun resolveStmt(stmt: Stmt?, scope: Scope) {
        if (stmt == null) return
        when (stmt) {
            is Stmt.Program -> {
                for (s in stmt.stmts) resolveStmt(s, scope)
            }

            is Stmt.UseStmt -> {
                // use / import 暂不处理
            }

            is Stmt.BlockStmt -> {
                val child = scope.child()
                for (s in stmt.stmts) resolveStmt(s, child)
            }

            is Stmt.ExprStmt -> {
                resolveExpr(stmt.expr, scope)
            }

            is Stmt.IfStmt -> {
                resolveExpr(stmt.condition, scope)
                resolveStmt(stmt.thenBranch, scope)
                resolveStmt(stmt.elseBranch, scope)
            }

            is Stmt.MatchStmt -> {
                resolveExpr(stmt.scrutinee, scope)
                stmt.branches?.let { branches ->
                    for (branch in branches) {
                        resolveExpr(branch.pattern, scope)
                        resolveStmt(branch.body, scope)
                    }
                }
            }

            is Stmt.ForStmt -> {
                // for 循环引入新作用域；flag 是循环标签，不是变量，不解析
                val child = scope.child()
                stmt.varDecl?.let { resolveLoopVar(it, child) }
                stmt.expr?.let { resolveExpr(it, child) }
                resolveStmt(stmt.body, child)
            }

            is Stmt.WhileStmt -> {
                // flag 是循环标签，不解析
                resolveExpr(stmt.expr, scope)
                resolveStmt(stmt.body, scope)
            }

            is Stmt.BreakStmt, is Stmt.ContinueStmt -> {
                // flag 是循环标签，不解析
            }

            is Stmt.FnStmt -> {
                resolveFnStmt(stmt, scope)
            }

            is Stmt.ReturnStmt -> {
                stmt.expr?.let { resolveExpr(it, scope) }
            }

            is Stmt.AssignStmt -> {
                resolveExpr(stmt.`var`, scope)
                resolveExpr(stmt.value, scope)
            }

            is Stmt.SetVarStmt -> {
                resolveSetVarStmt(stmt, scope)
            }
        }
    }

    /**
     * 函数声明：登记函数符号、挂载 TypeScheme、绑定形参、解析函数体。
     */
    private fun resolveFnStmt(stmt: Stmt.FnStmt, scope: Scope) {
        val fnName = (stmt.name?.literal as? String) ?: stmt.name?.type?.toString()
        if (fnName == null) {
            // 匿名/无名函数：只解析函数体，不登记
            resolveStmt(stmt.body, scope)
            return
        }

        val fnSymbol = declare(fnName, BuiltinType.Fn, stmt.name?.span ?: stmt.span, scope)
        stmt.defId = fnSymbol?.id

        // 挂载 TypeScheme：当前无泛型语法，typeVars 为空。
        // 未来 `fn foo<T>(...)` 就绪后，在此把泛型形参填入 TypeScheme.typeVars。
        fnSymbol?.typeScheme = TypeScheme(Seq(), BuiltinType.Fn)

        // 形参与函数体在子作用域中（未来泛型形参 `<T>` 也绑定在此，类型注解可引用）
        val fnScope = scope.child()
        stmt.parameters?.let { params ->
            for (p in params) bindParam(p, fnScope)
        }

        // 返回值声明中的类型注解：同样只做类型名的名称解析
        stmt.results?.let { results ->
            for (r in results) {
                if (r is Expr.Annotation) {
                    for (variant in r.annotations) resolveAnnotationNames(variant, fnScope)
                }
            }
        }

        resolveStmt(stmt.body, fnScope)
    }

    /**
     * 形参绑定：形参可能是 `Identifier` 或 `Annotation(Identifier, 注解...)`。
     * 绑定名称 → DefId，并把形参标识符的 defId 填好。
     */
    private fun bindParam(param: Expr, scope: Scope) {
        val ident = unwrapIdentifier(param) ?: return
        val name = (ident.token.literal as? String) ?: ident.token.type.toString()
        val symbol = declare(name, BuiltinType.Unknown, ident.span, scope)
        ident.defId = symbol?.id

        // 形参类型注解：这里只做「类型名 → DefId」的名称解析，
        // 注解 → Type 的转换与约束生成由 TypeInferencer 完成（职责分离，见 resolveAnnotationNames）。
        if (param is Expr.Annotation) {
            for (variant in param.annotations) resolveAnnotationNames(variant, scope)
        }
    }

    /**
     * 循环变量绑定（for 循环的 varDecl）。
     */
    private fun resolveLoopVar(varDecl: Expr.Identifier, scope: Scope) {
        val name = (varDecl.token.literal as? String) ?: varDecl.token.type.toString()
        val symbol = declare(name, BuiltinType.Unknown, varDecl.span, scope)
        varDecl.defId = symbol?.id
    }

    /**
     * `set` 声明变量：登记符号并绑定；随后解析其赋值语句。
     * var 可能是 `Identifier` 或 `Annotation(Identifier, ...)`。
     */
    private fun resolveSetVarStmt(stmt: Stmt.SetVarStmt, scope: Scope) {
        val ident = unwrapIdentifier(stmt.`var`)
        if (ident != null) {
            val name = (ident.token.literal as? String) ?: ident.token.type.toString()
            val symbol = declare(name, BuiltinType.Unknown, ident.span, scope)
            ident.defId = symbol?.id
        }
        resolveStmt(stmt.assignStmt, scope)
    }

    // ========== 表达式解析 ==========
    private fun resolveExpr(expr: Expr?, scope: Scope) {
        if (expr == null) return
        when (expr) {
            is Expr.Identifier -> {
                val name = (expr.token.literal as? String) ?: expr.token.type.toString()
                val defId = scope.lookup(name)
                if (defId == null) {
                    error("未声明的标识符: $name").point(expr, "未找到此标识符的定义")
                } else {
                    expr.defId = defId
                }
            }

            is Expr.Literal, is Expr.ErrorExpr -> Unit

            is Expr.Tuple -> {
                for (e in expr.elements) resolveExpr(e, scope)
            }

            is Expr.Annotation -> {
                // 只解析被注解的表达式主体；注解中的类型名由类型系统后续处理，
                // 这里不解析，避免把类型名误报为"未声明的标识符"。
                resolveExpr(expr.expr, scope)
            }

            is Expr.Unary -> resolveExpr(expr.expr, scope)

            is Expr.Binary -> {
                resolveExpr(expr.left, scope)
                resolveExpr(expr.right, scope)
            }

            is Expr.Array -> {
                for (e in expr.elements) resolveExpr(e, scope)
            }

            is Expr.Index -> {
                resolveExpr(expr.list, scope)
                resolveExpr(expr.index, scope)
            }

            is Expr.Range -> {
                resolveExpr(expr.left, scope)
                resolveExpr(expr.right, scope)
            }

            is Expr.Call -> {
                resolveExpr(expr.callee, scope)
                for (a in expr.arguments) resolveExpr(a, scope)
            }

            is Expr.Get -> {
                resolveExpr(expr.obj, scope)
                resolveExpr(expr.field, scope)
            }
        }
    }

    // ========== 工具 ==========
    /**
     * 从 `Identifier` 或 `Annotation(Identifier, ...)` 中取出标识符。
     */
    private fun unwrapIdentifier(expr: Expr): Expr.Identifier? {
        return when (expr) {
            is Expr.Identifier -> expr
            is Expr.Annotation -> expr.expr as? Expr.Identifier
            else -> null
        }
    }

    /**
     * 在当前作用域声明一个定义：分配 DefId、登记到符号表、绑定名称。
     * 若名称在当前作用域重复，报错并返回 null。
     */
    private fun declare(name: String, type: Type, span: Span, scope: Scope): Symbol? {
        if (scope.containsLocal(name)) {
            error("重复定义: $name").point(span, "此名称已在当前作用域声明")
            return null
        }
        val symbol = symbolTable.declare(name, type, span)
        scope.bind(name, symbol.id)
        return symbol
    }

    private fun error(text: String): SemanticProblem {
        val e = SemanticProblem(sourceMap, text, Problem.ProblemLevel.ERROR)
        problems.addError(e)
        return e
    }

    /**
     * 解析类型注解中的类型名（只做名称解析，不做类型推断）。
     * 递归处理 Annotation/Identifier/TypePath 等类型表达式。
     */
    private fun resolveAnnotationNames(expr: Expr, scope: Scope) {
        when (expr) {
            is Expr.Identifier -> {
                val name = (expr.token.literal as? String) ?: expr.token.type.toString()
                val defId = scope.lookup(name)
                if (defId == null) {
                    error("未声明的类型名: $name").point(expr, "未找到此类型名的定义")
                } else {
                    expr.defId = defId
                }
            }

            is Expr.Annotation -> {
                resolveAnnotationNames(expr.expr, scope)
                for (ann in expr.annotations) resolveAnnotationNames(ann, scope)
            }

            is Expr.Tuple -> {
                for (e in expr.elements) resolveAnnotationNames(e, scope)
            }

            is Expr.Array -> {
                for (e in expr.elements) resolveAnnotationNames(e, scope)
            }

            is Expr.Call -> {
                resolveAnnotationNames(expr.callee, scope)
                for (a in expr.arguments) resolveAnnotationNames(a, scope)
            }

            is Expr.Get -> {
                resolveAnnotationNames(expr.obj, scope)
                resolveAnnotationNames(expr.field, scope)
            }
            // 其他类型表达式暂不处理
            else -> {}
        }
    }
}
