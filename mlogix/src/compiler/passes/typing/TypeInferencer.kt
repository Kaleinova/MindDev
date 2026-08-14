package mlogix.compiler.passes.typing

import arc.struct.ObjectMap
import arc.struct.Seq
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.SourceMap.SourceFile
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.symbol.DefId
import mlogix.compiler.core.symbol.SymbolTable
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.core.type.BuiltinType
import mlogix.compiler.core.type.Type
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.diagnostic.Diagnostic.SemanticDiag
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.ir.ResolutionResult
import mlogix.util.I18N.bundle

/**
 * 类型推断：约束生成 + 惰性求解（HM 风格）。
 *
 * - **DefId 中心**：Resolver 已把每个 `Identifier` 解析为 [mlogix.compiler.core.symbol.DefId]，
 *   本 Pass 只通过 [SymbolTable]（Map<DefId, Symbol>）读写符号类型，绝不按名称查表。
 * - **sealed 类型**：所有类型比较用结构相等（`==`），类型变量为 [Type.Var]（Int 索引）。
 *
 * 一个项目 一次构造；一个文件 一次 [analyze]。
 */
class TypeInferencer(val problems: DiagHandler) {
    private lateinit var sourceFile: SourceFile
    private lateinit var symbolTable: SymbolTable
    private lateinit var solver: TypeSolver
    private val constraints = Seq<Constraint>()

    /** 当前函数返回上下文栈：期望返回类型 + 函数声明位置（用于 return 不匹配报错的声明方 label） */
    private val returnContextStack = Seq<ReturnContext>(2)

    /** 类型变量 → 声明位置：形参类型变量登记其注解 span，供调用处报错的声明方 label 使用 */
    private val varDeclSpans = ObjectMap<Int, Span>()

    // ========== 执行类型推断 ==========
    /**
     * 一个文件 一次分析
     *
     * @param result Resolver 的输出（作用域树 + 符号表），其中 AST 标识符已带 defId
     * @param sourceFile 当前源文件位置映射
     */
    fun analyze(result: ResolutionResult, sourceFile: SourceFile) {
        this.sourceFile = sourceFile
        this.symbolTable = result.symbolTable

        // prepare solver
        solver = TypeSolver(problems, sourceFile)
        constraints.clear()

        // walk AST and collect constraints
        analyzeStmt(result.ast)

        // solve collected constraints
        solver.solveEqualities(constraints)

        // propagate solved inferred types back to symbols
        for (symbol in symbolTable.all()) {
            val inferred = symbol.values.get("inferred") as? Type
            if (inferred != null) {
                val final = solver.read(inferred)
                if (final !is Type.Var) {
                    // update symbol type if previously Unknown
                    if (symbol.type == BuiltinType.Unknown) symbol.type = final
                    symbol.values.put("final", final)
                }
            } else if (symbol.type is Type.Var) {
                // 形参等直接挂类型变量的符号：求解后把具体类型写回（如 `a: Int` → Con("Int")）
                val final = solver.read(symbol.type)
                if (final !is Type.Var) {
                    symbol.type = final
                    symbol.values.put("final", final)
                }
            }
        }
    }

    private fun analyzeStmt(stmt: Stmt?) {
        if (stmt == null) return
        when (stmt) {
            is Stmt.Program -> {
                // program: process each top-level statement
                for (s in stmt.stmts) analyzeStmt(s)
            }

            is Stmt.UseStmt -> {
                // imports / use ignored by current analyzer
            }

            is Stmt.BlockStmt -> {
                for (s in stmt.stmts) analyzeStmt(s)
            }

            is Stmt.ExprStmt -> {
                val expr = inferExpr(stmt.expr)
                for (c in expr.constraints) constraints.add(c)
            }

            is Stmt.IfStmt -> {
                val cond = inferExpr(stmt.condition)
                for (c in cond.constraints) constraints.add(c)
                constraints.add(Constraint.Equal(cond.type, BuiltinType.Bool, stmt.condition.span))
                analyzeStmt(stmt.thenBranch)
                analyzeStmt(stmt.elseBranch)
            }

            is Stmt.MatchStmt -> {
                val scr = inferExpr(stmt.scrutinee)
                for (c in scr.constraints) constraints.add(c)
                stmt.branches?.let { brs ->
                    for ((_, _, body) in brs) {
                        analyzeStmt(body)
                    }
                }
            }

            is Stmt.ForStmt -> {
                // flag 是循环标签，不是变量，不推断
                stmt.varDecl?.let { val r = inferExpr(it); for (c in r.constraints) constraints.add(c) }
                stmt.expr?.let { val r = inferExpr(it); for (c in r.constraints) constraints.add(c) }
                analyzeStmt(stmt.body)
            }

            is Stmt.WhileStmt -> {
                // flag 是循环标签，不推断
                val cond = inferExpr(stmt.expr)
                for (c in cond.constraints) constraints.add(c)
                constraints.add(Constraint.Equal(cond.type, BuiltinType.Bool, stmt.expr.span))
                analyzeStmt(stmt.body)
            }

            is Stmt.BreakStmt, is Stmt.ContinueStmt -> {
                // nothing
            }

            is Stmt.FnStmt -> {
                analyzeFnStmt(stmt)
            }

            is Stmt.ReturnStmt -> {
                val exprR = stmt.expr?.let { inferExpr(it) }
                exprR?.let { for (c in it.constraints) constraints.add(c) }
                if (!returnContextStack.isEmpty) {
                    val context = returnContextStack.peek()
                    val returnType = exprR?.type ?: BuiltinType.Null
                    // t1=实际返回类型(使用方)，t2=函数声明返回类型(声明方)，declPos=函数声明位置
                    constraints.add(Constraint.Equal(returnType, context.expected, stmt.span, context.declSpan))
                }
            }

            is Stmt.AssignStmt -> {
                // analyze both sides
                val lr = inferExpr(stmt.`var`)
                for (c in lr.constraints) constraints.add(c)
                val valueR = inferExpr(stmt.value)
                for (c in valueR.constraints) constraints.add(c)

                // if left side is a simple identifier, enforce/collect type constraints
                val lhsIdent = unwrapIdentifier(stmt.`var`)
                if (lhsIdent != null) {
                    val defId = lhsIdent.defId
                    if (defId == null) {
                        val lhsName = (lhsIdent.token.literal as? String) ?: lhsIdent.token.type.toString()
                        error(bundle.format("diag.undeclared-variable", lhsName))
                            .label(lhsIdent, bundle.get("diag.undeclared-variable.help"))
                    } else {
                        val symbol = symbolTable.get(defId)
                        if (symbol != null) {
                            // derive a left-side type; if unknown, create a fresh type variable
                            var leftType: Type = symbol.type
                            if (leftType == BuiltinType.Unknown) {
                                leftType = solver.freshVar()
                                symbol.values.put("inferred", leftType)
                            }
                            // 使用方=实际 RHS 类型（label 于 RHS 处），声明方=变量声明类型（label 于符号声明处）
                            constraints.add(Constraint.Equal(valueR.type, leftType, stmt.value.span, symbol.span))
                        }
                    }
                }
                // non-identifier LHS (e.g. indexing, field access): subexpressions already analyzed above.
            }

            is Stmt.SetVarStmt -> {
                // set introduces a new variable in current scope; its symbol was created by Resolver
                val valueType =
                    if (stmt.assignStmt != null) analyzeExpr((stmt.assignStmt as Stmt.AssignStmt).value) else BuiltinType.Unknown
                val varIdent = unwrapIdentifier(stmt.`var`)
                varIdent?.defId?.let { defId ->
                    val symbol = symbolTable.get(defId)
                    if (symbol != null && valueType != BuiltinType.Unknown) {
                        symbol.type = valueType
                    }
                }
                // if there is an assignStmt (a nested AssignStmt) analyze it too
                analyzeStmt(stmt.assignStmt)
            }

            else -> {
                // unhandled statement kinds
            }
        }
    }

    /**
     * 函数声明：为函数符号构造 [Type.Func]，绑定形参类型，分析函数体。
     *
     * 形参/返回值类型注解的职责分工：
     * - Resolver 已把注解中的类型名解析为 [DefId]（填在 [Expr.Identifier.defId]）；
     * - 本方法只做「已解析注解表达式 → [Type]」的转换（[annotationToType]）并生成
     *   [Constraint.Equal]（使用方 = 形参类型变量，声明方 = 注解位置）；
     * - [TypeSolver] 只负责求解，不做名字解析、不做类型构造。
     */
    private fun analyzeFnStmt(stmt: Stmt.FnStmt) {
        val fnSymbol = stmt.defId?.let { symbolTable.get(it) }
        if (fnSymbol == null) {
            // 无名函数或解析失败：仍尝试分析函数体
            analyzeStmt(stmt.body)
            return
        }

        // 形参类型：无注解 → 类型变量；有注解 → 类型变量 + Equal(变量, 注解类型) 约束
        val paramTypes = Seq<Type>(8)
        stmt.parameters?.let { params ->
            for (p in params) {
                val tv = solver.freshVar()
                paramTypes.add(tv)
                if (p is Expr.Annotation) {
                    val declType = annotationToType(p)
                    val useSpan = unwrapIdentifier(p)?.span ?: p.span
                    // 登记：该类型变量的声明位置 = 形参注解整体 span（调用处报错的声明方 label 用）
                    varDeclSpans.put(tv.index, p.span)
                    // t1=形参实际类型(使用方)，t2=注解声明的类型(声明方)
                    constraints.add(Constraint.Equal(tv, declType, useSpan, p.span))
                }
            }
        }

        // 返回值类型：无注解 → 类型变量；单一返回值有注解 → 约束 resultType == 注解类型。
        // 多返回值（`-> a: T1, b: T2`）尚未建模（Type.Func 只有单一 result），暂不约束。
        val resultType = solver.freshVar()
        fnSymbol.type = Type.Func(paramTypes, resultType)
        stmt.results?.let { results ->
            if (results.size == 1) {
                val result = results[0]
                if (result is Expr.Annotation) {
                    val declType = annotationToType(result)
                    constraints.add(Constraint.Equal(resultType, declType, result.span, result.span))
                }
            }
        }

        // analyze body with parameters bound
        returnContextStack.add(ReturnContext(resultType, stmt.name?.span ?: stmt.span))
        stmt.parameters?.let { params ->
            for ((i, p) in params.withIndex()) {
                val ident = unwrapIdentifier(p)
                ident?.defId?.let { defId ->
                    symbolTable.get(defId)?.let { paramSymbol ->
                        paramSymbol.type = paramTypes.get(i)
                    }
                }
            }
        }
        analyzeStmt(stmt.body)
        // pop return context
        returnContextStack.pop()
    }

    private fun analyzeExpr(expr: Expr?): Type {
        // keep compatibility wrapper: call inferExpr and return type
        return inferExpr(expr).type
    }

    private fun inferExpr(expr: Expr?): InferResult {
        if (expr == null) return InferResult(BuiltinType.Unknown, Seq<Constraint>(0))

        return when (expr) {
            is Expr.Literal -> InferResult(BuiltinType.toType(expr.token.type), Seq(0))

            is Expr.Identifier -> {
                val defId = expr.defId
                if (defId == null) {
                    val name = (expr.token.literal as? String) ?: expr.token.type.toString()
                    error(bundle.format("diag.undeclared-identifier", name))
                        .label(expr, bundle.get("diag.undeclared-identifier.help"))
                    InferResult(BuiltinType.Unknown, Seq(0))
                } else {
                    val symbol = symbolTable.get(defId)
                    if (symbol == null) {
                        InferResult(BuiltinType.Unknown, Seq(0))
                    } else {
                        var ty = symbol.type
                        if (ty == BuiltinType.Unknown) {
                            // create type variable to be inferred
                            ty = solver.freshVar()
                            symbol.values.put("inferred", ty)
                        }
                        InferResult(ty, Seq(0))
                    }
                }
            }

            is Expr.Tuple -> {
                // 元组：逐元素推断，产出 Type.TupleType
                val combined = Seq<Constraint>(0)
                val elementTypes = Seq<Type>(0)
                for (e in expr.elements) {
                    val r = inferExpr(e)
                    for (c in r.constraints) combined.add(c)
                    elementTypes.add(r.type)
                }
                InferResult(Type.TupleType(elementTypes), combined)
            }

            is Expr.Annotation -> {
                val r = inferExpr(expr.expr)
                InferResult(r.type, r.constraints)
            }

            is Expr.Unary -> {
                val r = inferExpr(expr.expr)
                InferResult(BuiltinType.Unknown, r.constraints)
            }

            is Expr.Binary -> {
                val l = inferExpr(expr.left)
                val r = inferExpr(expr.right)
                val combined = Seq<Constraint>(0)
                for (c in l.constraints) combined.add(c)
                for (c in r.constraints) combined.add(c)

                val resultType = if (expr.operator.type in setOf(
                        TokenType.GREATER,
                        TokenType.GREATER_EQ,
                        TokenType.LESS,
                        TokenType.LESS_EQ,
                        TokenType.EQ_EQ,
                        TokenType.BANG_EQ
                    )
                ) {
                    BuiltinType.Bool
                } else if (l.type != BuiltinType.Unknown && r.type != BuiltinType.Unknown) {
                    getResultType(expr.operator, l.type, r.type)
                } else {
                    solver.freshVar()
                }

                InferResult(resultType, combined)
            }

            is Expr.Array -> {
                // 数组字面量：所有元素统一为一个元素类型，产出 Type.Arr(elementType)
                val combined = Seq<Constraint>(0)
                val elemVar = solver.freshVar()
                for (e in expr.elements) {
                    val r = inferExpr(e)
                    for (c in r.constraints) combined.add(c)
                    // 使用方=元素实际类型，声明方=统一的元素类型变量
                    combined.add(Constraint.Equal(r.type, elemVar, e.span))
                }
                InferResult(Type.Arr(elemVar), combined)
            }

            is Expr.Index -> {
                // 索引：list 必须是 Array<result>，index 必须是 Int
                val l = inferExpr(expr.list)
                val index = inferExpr(expr.index)
                val combined = Seq<Constraint>(0)
                for (c in l.constraints) combined.add(c)
                for (c in index.constraints) combined.add(c)
                val elemVar = solver.freshVar()
                combined.add(Constraint.Equal(l.type, Type.Arr(elemVar), expr.list.span))
                combined.add(Constraint.Equal(index.type, BuiltinType.Int, expr.index.span))
                InferResult(elemVar, combined)
            }

            is Expr.Range -> {
                val combined = Seq<Constraint>(0)
                expr.left?.let { for (c in inferExpr(it).constraints) combined.add(c) }
                expr.right?.let { for (c in inferExpr(it).constraints) combined.add(c) }
                InferResult(BuiltinType.Unknown, combined)
            }

            is Expr.Call -> {
                val callee = inferExpr(expr.callee)
                val combined = Seq<Constraint>(0)
                for (c in callee.constraints) combined.add(c)

                // 实参类型：逐个推断
                val argTypes = Seq<Type>(expr.arguments.size)
                for (a in expr.arguments) {
                    val ar = inferExpr(a)
                    for (c in ar.constraints) combined.add(c)
                    argTypes.add(ar.type)
                }

                // 若 callee 是已知具名函数：取「函数名位置 + 形参声明位置列表」用于报错定位
                val calleeInfo = paramDeclSpansOf(expr.callee)

                // 1) 结构链接：callee 必须是「实参数量对应的函数类型」。
                //    每个形参用 fresh 变量占位，等待与实参逐一约束。
                //    t1=调用处合成的函数类型（使用方/实际），t2=函数声明类型（声明方/期望）。
                val paramVars = Seq<Type>(argTypes.size)
                repeat(argTypes.size) { paramVars.add(solver.freshVar()) }
                val resVar = solver.freshVar()
                val fnType = Type.Func(paramVars, resVar)
                combined.add(Constraint.Equal(fnType, callee.type, expr.callee.span, calleeInfo?.first))

                // 2) 逐实参约束：使用方=实参自身 span（label），声明方=形参注解位置（label）。
                //    这样每个不匹配的实参单独报错，而不是整个 Call 一个错误。
                val paramDeclSpans = calleeInfo?.second
                val declSpanCount = paramDeclSpans?.size ?: 0
                for ((i, element) in argTypes.withIndex()) {
                    // 实参数量可能超过形参声明数量（结构链接约束会另行报「参数数量不匹配」），
                    // 越界的实参没有对应形参声明位置 → declSpan 取 null（退化为仅使用方 label）。
                    // （paramDeclSpans 为 null 时 declSpanCount=0，`i < 0` 恒假，?. 保证不越界）
                    val declSpan = if (i < declSpanCount) paramDeclSpans?.get(i) else null
                    combined.add(
                        Constraint.Equal(
                            element,
                            paramVars[i],
                            expr.arguments[i].span,
                            declSpan,
                        )
                    )
                }

                InferResult(resVar, combined)
            }

            is Expr.Get -> {
                val ot = inferExpr(expr.obj)
                val combined = Seq<Constraint>(0)
                combined.addAll(ot.constraints)
                val fieldName = if (expr.field is Expr.Identifier) (expr.field.token.literal as? String) else null
                if (ot.type is Type.Arr && fieldName == "length") {
                    InferResult(BuiltinType.Int, combined)
                } else {
                    InferResult(BuiltinType.Unknown, combined)
                }
            }

            is Expr.ErrorExpr -> InferResult(BuiltinType.Unknown, Seq(0))
            else -> InferResult(BuiltinType.Unknown, Seq(0))
        }
    }

    // region tools

    // 根据操作符和操作数类型确定结果类型
    private fun getResultType(operator: Token, leftType: Type?, rightType: Type?): Type {
        when (operator.type) {
            TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH -> {
                if (leftType == BuiltinType.Num || rightType == BuiltinType.Num) {
                    return BuiltinType.Num
                }
                return BuiltinType.Int
            }

            TokenType.GREATER, TokenType.GREATER_EQ, TokenType.LESS, TokenType.LESS_EQ, TokenType.EQ_EQ, TokenType.BANG_EQ -> return BuiltinType.Bool
            else -> return BuiltinType.Unknown
        }
    }

    /**
     * 形参/返回值类型注解 → [Type]。
     *
     * 职责边界（Resolver / TypeSolver 分工）：
     * - Resolver 只做「类型名 → [DefId]」的名称解析（填在 [Expr.Identifier.defId]），
     *   本方法**不做任何名字解析**，只查 [SymbolTable] 完成「注解表达式 → Type」的转换；
     * - 生成的 [Constraint.Equal] 由 [TypeSolver] 统一求解。
     *
     * 当前限制：注解语法是匿名枚举（多个枚举值，如 `Int | Str`、`?(Num Str)`），
     * 而类型系统尚未引入联合/枚举类型，因此：
     * - 单一枚举值（`a: Int`、`r: (Num, Str)`）→ 正常转换为 [Type]；
     * - 多个枚举值 → 报「暂不支持」错误并返回 [Type.Error]（抑制级联错误）。
     *
     * @param annotation 形参/返回值的 `Expr.Annotation` 节点（内部注解即枚举值列表）
     * @return 注解对应的类型；无注解/无法转换时返回相应占位类型
     */
    private fun annotationToType(annotation: Expr.Annotation): Type {
        val variants = annotation.annotations
        if (variants.isEmpty) return BuiltinType.Unknown
        if (variants.size > 1) {
            error(bundle.get("diag.union-not-supported"))
                .label(annotation, bundle.get("diag.union-not-supported.help"))
            return Type.Error
        }
        return variantToType(variants[0])
    }

    /**
     * 单个枚举值表达式 → [Type]。
     * - 标识符：经 [DefId] 查 [SymbolTable] 得符号类型；`Array` 特化为 [Type.Arr]
     *   （实际数组类型是 `Arr` 而非 `Con("Array")`）；
     * - 元组：递归转换元素，产出 [Type.TupleType]；
     * - 无法转换（defId 缺失 / 符号不存在 / 其它表达式）：返回 [Type.Error]。
     */
    private fun variantToType(expr: Expr): Type {
        return when (expr) {
            is Expr.Identifier -> {
                val symbol = expr.defId?.let { symbolTable.get(it) }
                when (symbol?.type) {
                    BuiltinType.Array -> Type.Arr(solver.freshVar())
                    else -> symbol?.type ?: Type.Error
                }
            }

            is Expr.Tuple -> {
                val elements = Seq<Type>(0)
                for (e in expr.elements) elements.add(variantToType(e))
                Type.TupleType(elements)
            }

            else -> Type.Error
        }
    }

    // 从 `Identifier` 或 `Annotation(Identifier, ...)` 中取出标识符
    private fun unwrapIdentifier(expr: Expr): Expr.Identifier? {
        return when (expr) {
            is Expr.Identifier -> expr
            is Expr.Annotation -> expr.expr as? Expr.Identifier
            else -> null
        }
    }

    /**
     * 若 [calleeExpr] 是已知具名函数（Identifier 且符号类型为 [Type.Func]），
     * 返回 `函数名位置` 与 `形参声明位置列表`（与形参类型变量一一对应），
     * 供调用处报错的声明方 label 定位。
     *
     * 形参位置取自 [varDeclSpans]（形参类型变量 → 注解 span，在 [analyzeFnStmt] 登记）；
     * 无注解的形参没有「期望类型声明处」，对应位置为 null。
     *
     * @return null 表示 callee 不是已知具名函数（如 lambda、方法引用），
     *         调用处报错将退化为只有使用方 label、没有声明方 label。
     */
    private fun paramDeclSpansOf(calleeExpr: Expr): Pair<Span, Seq<Span?>>? {
        val ident = calleeExpr as? Expr.Identifier ?: return null
        val symbol = ident.defId?.let { symbolTable.get(it) } ?: return null
        val fnType = symbol.type as? Type.Func ?: return null
        val spans = Seq<Span?>(fnType.params.size)
        for (p in fnType.params) {
            val v = p as? Type.Var
            spans.add(if (v == null) null else varDeclSpans.get(v.index))
        }
        return symbol.span to spans
    }

    // 错误
    private fun error(name: String): SemanticDiag {
        val e = SemanticDiag(name, Diagnostic.DiagLevel.ERROR)
        problems.addError(e)
        return e
    }

    // 警告
    private fun warning(name: String): SemanticDiag {
        val w = SemanticDiag(name, Diagnostic.DiagLevel.WARNING)
        problems.addWarning(w)
        return w
    }

    // 当前函数返回上下文：期望返回类型 + 函数声明位置（不匹配报错的声明方 label 用）
    private data class ReturnContext(val expected: Type, val declSpan: Span)

    // endregion
}