package mlogix.compiler.passes.typing

import arc.struct.IntSet
import arc.struct.ObjectMap
import arc.struct.Seq
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.SourceMap.SourceFile
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.symbol.DefId
import mlogix.compiler.core.symbol.Symbol
import mlogix.compiler.core.symbol.SymbolTable
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.core.type.BuiltinType
import mlogix.compiler.core.type.Type
import mlogix.compiler.core.type.TypeScheme
import mlogix.compiler.core.type.TypeVisitor
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.diagnostic.Diagnostic.SemanticDiag
import mlogix.compiler.ir.ResolutionResult
import mlogix.util.I18N.bundle

/**
 * 类型推断：约束生�?+ 惰性求解（HM 风格）�?
 *
 * - **DefId 中心**：Resolver 已把每个 `Identifier` 解析�?[mlogix.compiler.core.symbol.DefId]�?
 *   �?Pass 只通过 [SymbolTable]（Map<DefId, Symbol>）读写符号类型，绝不按名称查表�?
 * - **sealed 类型**：所有类型比较用结构相等（`==`），类型变量�?[Type.Var]（Int 索引）�?
 *
 * 一个项�?一次构造；一个文�?一�?[analyze]�?
 */
class TypeInferencer(val problems: DiagHandler) {
    private lateinit var sourceFile: SourceFile
    private lateinit var symbolTable: SymbolTable
    private lateinit var solver: TypeSolver
    private val constraints = Seq<Constraint>()

    /** 当前函数返回上下文栈：期望返回类�?+ 函数声明位置（用�?return 不匹配报错的声明�?label�?*/
    private val returnContextStack = Seq<ReturnContext>(2)

    /** 类型变量 �?声明位置：形参类型变量登记其注解 span，供调用处报错的声明�?label 使用 */
    private val varDeclSpans = ObjectMap<Int, Span>()

    /** 泛型函数登记表：walk 阶段登记，求解完成后统一重建 TypeScheme（见 [analyze] 末尾�?*/
    private val genericFns = Seq<GenericFnInfo>(4)

    /** 泛型参数嵌套栈：每帧 = 当前函数声明的类型参数变量；用于泛化时排除外层函数的类型参数 */
    private val typeParamStack = Seq<Seq<Type.Var>>(2)

    // ========== 执行类型推断 ==========
    /**
     * 一个文�?一次分�?
     *
     * @param result Resolver 的输出（作用域树 + 符号表），其�?AST 标识符已�?defId
     * @param sourceFile 当前源文件位置映�?
     */
    fun analyze(result: ResolutionResult, sourceFile: SourceFile) {
        this.sourceFile = sourceFile
        this.symbolTable = result.symbolTable

        // prepare solver
        solver = TypeSolver(problems, sourceFile)
        constraints.clear()
        genericFns.clear()
        typeParamStack.clear()

        // walk AST and collect constraints
        analyzeStmt(result.ast)

        // solve collected constraints
        solver.solveEqualities(constraints)

        // 泛型函数：求解完成后，基�?已求解的 body"重新泛化�?
        // 为什么需要这步：类型方案�?walk 阶段（函数体分析前）暂挂，此时求解器尚未合并任何约束�?
        // 若直接使用暂挂的 body（如 `fn f<T>(x: T) { return x }` �?Func([T], resultVar)），
        // 结果变量 resultVar 未量化、会被所有调用点共享，导致第二次调用类型冲突�?
        // 求解�?body 中的 resultVar 已折叠为类型参数 T，泛化只量化真正自由的变量�?
        for (info in genericFns) {
            val solvedBody = solver.read(info.symbol.type)
            val generalized = TypeScheme(info.declaredVars, solvedBody).generalize(info.envFreeVars)
            info.symbol.typeScheme = TypeScheme(mergeTypeVars(info.declaredVars, generalized.typeVars), solvedBody)
            info.symbol.type = solvedBody
        }

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
            } else if (symbol.type is Type.Var &&
                symbol.values.get(Symbol.TYPE_PARAM_KEY) != true
            ) {
                // 形参等直接挂类型变量的符号：求解后把具体类型写回（如 `a: Int` �?Con("Int")）�?
                // 类型参数符号除外——它的类型必须保持为量化变量，供注解 `x: T` 引用�?
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
                constraints.addAll(expr.constraints)
            }

            is Stmt.IfStmt -> {
                val cond = inferExpr(stmt.condition)
                constraints.addAll(cond.constraints)
                constraints.add(Constraint.Equal(cond.type, BuiltinType.Bool, stmt.condition.span))
                analyzeStmt(stmt.thenBranch)
                analyzeStmt(stmt.elseBranch)
            }

            is Stmt.MatchStmt -> {
                val scrutinee = inferExpr(stmt.scrutinee)
                constraints.addAll(scrutinee.constraints)
                stmt.branches?.let { brs ->
                    for ((_, _, body) in brs) {
                        analyzeStmt(body)
                    }
                }
            }

            is Stmt.ForStmt -> {
                // flag 是循环标签，不是变量，不推断
                stmt.varDecl?.let { val r = inferExpr(it); constraints.addAll(r.constraints) }
                stmt.expr?.let { val r = inferExpr(it); constraints.addAll(r.constraints) }
                analyzeStmt(stmt.body)
            }

            is Stmt.WhileStmt -> {
                // flag 是循环标签，不推�?
                val cond = inferExpr(stmt.expr)
                constraints.addAll(cond.constraints)
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
                exprR?.let {constraints.addAll(it.constraints) }
                if (!returnContextStack.isEmpty) {
                    val context = returnContextStack.peek()
                    val returnType = exprR?.type ?: BuiltinType.Null
                    // t1=实际返回类型(使用�?，t2=函数声明返回类型(声明�?，declPos=函数声明位置
                    constraints.add(Constraint.Equal(returnType, context.expected, stmt.span, context.declSpan))
                }
            }

            is Stmt.AssignStmt -> {
                // analyze both sides
                val lr = inferExpr(stmt.`var`)
                constraints.addAll(lr.constraints)
                val valueR = inferExpr(stmt.value)
                constraints.addAll(valueR.constraints)

                // if left side is a simple identifier, enforce/collect type constraints
                val lhsIdent = unwrapIdentifier(stmt.`var`)
                if (lhsIdent != null) {
                    val defId = lhsIdent.defId
                    if (defId != null) {
                        val symbol = symbolTable.get(defId)
                        if (symbol != null) {
                            // derive a left-side type; if unknown, create a fresh type variable
                            var leftType: Type = symbol.type
                            if (leftType == BuiltinType.Unknown) {
                                leftType = solver.freshVar()
                                symbol.values.put("inferred", leftType)
                            }
                            // 使用�?实际 RHS 类型（label �?RHS 处），声明方=变量声明类型（label 于符号声明处�?
                            constraints.add(Constraint.Equal(valueR.type, leftType, stmt.value.span, symbol.span))
                        }
                    }
                }
                // TODO non-identifier LHS (e.g. indexing, field access): subexpressions already analyzed above.
            }

            is Stmt.SetVarStmt -> {
                val assign = stmt.assignStmt
                if (assign == null) {
                    // `set a`（无赋值）：符号保�?Unknown�?
                    // TODO: `set` 的类型注解尚未参与类型检查（既有行为，与泛型无关）：
                    //  `set a : Int = "str"` 不会报错，`set a : Array<Int> = {1, 2}` 的元素类型也不被检查�?
                    //  接入方式：单枚举值注解转�?Type 并与符号类型�?Equal 约束（多枚举值走 union-not-supported）�?
                    return
                }
                // �?AssignStmt 相同的推断，但每个表达式只推断一次并做符号类型快速传播：
                // 递归 analyzeStmt(assignStmt) 会让同一 RHS 被推断两次、其上的错误重复上报�?
                val lr = inferExpr(assign.`var`)
                constraints.addAll(lr.constraints)
                val valueR = inferExpr(assign.value)
                constraints.addAll(valueR.constraints)
                // 快速传播：后续语句读取该变量时能直接看到（推断出的）类�?
                // （如 `set a = 1; set b = a` �?b 能看�?a 已是 Int�?
                val varIdent = unwrapIdentifier(assign.`var`)
                varIdent?.defId?.let { defId ->
                    val symbol = symbolTable.get(defId)
                    if (symbol != null && valueR.type != BuiltinType.Unknown) {
                        symbol.type = valueR.type
                    }
                }
                // TODO: `set` 的类型注解尚未参与类型检查（既有行为，与泛型无关）：
                //  `set a : Int = "str"` 不会报错，`set a : Array<Int> = {1, 2}` 的元素类型也不被检查�?
                //  接入方式：单枚举值注解转�?Type 并与符号类型�?Equal 约束（多枚举值走 union-not-supported）�?
            }

            else -> {
                // unhandled statement kinds
            }
        }
    }

    /**
     * 函数声明：为函数符号构�?[Type.Func]，绑定形参类型，分析函数体�?
     *
     * 泛型形参（`fn foo<T, E>`）：
     * - 每个类型参数分配一�?fresh 类型变量，写入类型参数符号（注解 `x: T` 经符号查得该变量）；
     * - **泛型函数签名直接使用注解类型**（`x: T` �?形参类型就是 T 的变量），不引入中间变量�?
     *   保证按调用点实例化时 T 被正确替换、不跨调用点共享�?
     * - 暂挂 TypeScheme 时把签名中全部自由变量（类型参数 + 未注解形�?返回值变量）一并量化，
     *   供函数体递归调用实例化；求解完成后在 [analyze] 末尾基于已求解的 body 重新泛化�?
     *
     * 非泛型函数维持既有行为：形参�?fresh 变量 + Equal(注解) 约束（调用处声明�?label 定位依赖它）�?
     *
     * 形参/返回值类型注解的职责分工�?
     * - Resolver 已把注解中的类型名解析为 [DefId]（填�?[Expr.Identifier.defId]）；
     * - 本方法只做「已解析注解表达�?�?[Type]」的转换（[annotationToType]）；
     * - [TypeSolver] 只负责求解，不做名字解析、不做类型构造�?
     */
    private fun analyzeFnStmt(stmt: Stmt.FnStmt) {
        val fnSymbol = stmt.defId?.let { symbolTable.get(it) }
        if (fnSymbol == null) {
            // 无名函数或解析失败：仍尝试分析函数体
            analyzeStmt(stmt.body)
            return
        }

        val typeParams = stmt.typeParams
        val isGeneric = typeParams != null && !typeParams.isEmpty

        // 泛型形参：每个类型参数分�?fresh 变量并写入类型参数符号�?
        // 声明处嵌�?`E<U>`（高阶类型）已在 Resolver 报错，这里按声明顺序只绑定头部名字�?
        val typeParamVars = Seq<Type.Var>(typeParams?.size ?: 0)
        if (typeParams != null && isGeneric) {
            for (typeParam in typeParams) {
                val v = solver.freshVar()
                typeParamVars.add(v)
                // 类型参数自身的声明位置也登记，供调用处报错的声明�?label 使用
                varDeclSpans.put(v.index, typeParam.span)
                typeParam.defId?.let { defId ->
                    symbolTable.get(defId)?.let { paramSymbol -> paramSymbol.type = v }
                }
            }
        }

        // 形参类型
        val paramTypes = Seq<Type>(8)
        stmt.params?.let { params ->
            for (p in params) {
                if (p is Expr.Annotation) {
                    val declType = annotationToType(p)
                    if (isGeneric) {
                        // 泛型函数：注解类型直接作为形参类型（`x: T` �?T 的变量）
                        paramTypes.add(declType)
                        registerVarSpan(declType, p.span)
                    } else {
                        // 非泛型：tv + Equal(变量, 注解类型) 约束（维持既有行为）
                        val tv = solver.freshVar()
                        paramTypes.add(tv)
                        val useSpan = unwrapIdentifier(p)?.span ?: p.span
                        // 登记：该类型变量的声明位�?= 形参注解整体 span（调用处报错的声明方 label 用）
                        varDeclSpans.put(tv.index, p.span)
                        // t1=形参实际类型(使用�?，t2=注解声明的类�?声明�?
                        constraints.add(Constraint.Equal(tv, declType, useSpan, p.span))
                    }
                } else {
                    // 无注解：fresh 变量（泛型函数中它会并入暂挂 scheme �?typeVars�?
                    paramTypes.add(solver.freshVar())
                }
            }
        }

        // 返回值类型：无注�?�?fresh 变量；单一返回值有注解 �?注解类型（泛型）�?Equal 约束（非泛型）�?
        // 多返回值（`-> a: T1, b: T2`）尚未建模（Type.Func 只有单一 result），暂不约束�?
        var resultType: Type = solver.freshVar()
        stmt.results?.let { results ->
            if (results.size == 1) {
                val result = results[0]
                if (result is Expr.Annotation) {
                    val declType = annotationToType(result)
                    if (isGeneric) {
                        resultType = declType
                        registerVarSpan(declType, result.span)
                    } else {
                        constraints.add(Constraint.Equal(resultType, declType, result.span, result.span))
                    }
                }
            }
        }

        fnSymbol.type = Type.Func(paramTypes, resultType)

        // 泛型函数：暂�?scheme（供递归调用实例化），并登记�?genericFns�?
        // 求解完成后统一重建�?已泛化的 scheme"（见 analyze() 末尾）�?
        if (isGeneric) {
            val quantified = mergeTypeVars(typeParamVars, signatureFreeVars(fnSymbol.type, typeParamVars))
            fnSymbol.typeScheme = TypeScheme(quantified, fnSymbol.type)
            fnSymbol.values.put(Symbol.TYPE_PARAM_COUNT_KEY, typeParamVars.size)
            genericFns.add(GenericFnInfo(fnSymbol, typeParamVars, envFreeVars()))
            typeParamStack.add(typeParamVars)
        }

        // analyze body with parameters bound
        returnContextStack.add(ReturnContext(resultType, stmt.name?.span ?: stmt.span))
        stmt.params?.let { params ->
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

        if (isGeneric) typeParamStack.pop()
    }

    /**
     * �?[type] 是类型变量，把其声明位置登记�?[varDeclSpans]（调用处报错的声明方 label 用）�?
     */
    private fun registerVarSpan(type: Type, span: Span) {
        if (type is Type.Var) varDeclSpans.put(type.index, span)
    }

    /**
     * 收集 [fnType]（泛型函数签名）中出现的、不�?[declared] 中的自由类型变量
     * （未注解形参/返回值引入的变量）。暂�?scheme 时把这些变量一并量化，
     * 实例化时统一替换�?fresh 变量，避免跨调用点共享�?
     */
    private fun signatureFreeVars(fnType: Type, declared: Seq<Type.Var>): Seq<Type.Var> {
        val declaredSet = ObjectMap<Int, Boolean>()
        for (v in declared) declaredSet.put(v.index, true)
        val result = Seq<Type.Var>()
        val seen = ObjectMap<Int, Boolean>()
        fnType.accept(object : TypeVisitor {
            override fun visitVar(type: Type.Var) {
                if (!declaredSet.containsKey(type.index) && !seen.containsKey(type.index)) {
                    seen.put(type.index, true)
                    result.add(type)
                }
            }
        })
        return result
    }

    private fun inferExpr(expr: Expr?): InferResult {
        if (expr == null) return InferResult(BuiltinType.Unknown, Seq<Constraint>(0))

        return when (expr) {
            is Expr.Literal -> InferResult(BuiltinType.toType(expr.token.type), Seq(0))

            is Expr.Identifier -> {
                val defId = expr.defId
                if (defId == null) {
                    // Resolver 已报 diag.undeclared-identifier（名称解析归它管），
                    // 这里静默降级�?Error，避免同一错误重复上报�?
                    InferResult(BuiltinType.Error, Seq(0))
                } else {
                    val symbol = symbolTable.get(defId)
                    if (symbol == null) {
                        InferResult(BuiltinType.Unknown, Seq(0))
                    } else if (symbol.values.get(Symbol.TYPE_PARAM_KEY) == true) {
                        // 类型参数只能出现在类型位置（注解/类型实参），不能作为值使�?
                        error(bundle.format("diag.type-param-as-value", symbol.name))
                            .label(expr, "")
                        InferResult(BuiltinType.Error, Seq(0))
                    } else {
                        // 泛型函数（或值位置携带显式类型实参）：按调用点实例化类型方案�?
                        // 每次引用得到独立的类型变量（多态）�?
                        val explicitArgs = expr.typeArgs
                        val hasExplicit = explicitArgs != null && !explicitArgs.isEmpty
                        val declaredCount = symbol.values.get(Symbol.TYPE_PARAM_COUNT_KEY) as? Int ?: 0
                        if (!symbol.typeScheme.typeVars.isEmpty || declaredCount != 0 || hasExplicit) {
                            InferResult(instantiateScheme(symbol, expr, declaredCount), Seq(0))
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
            }

            is Expr.Tuple -> {
                // 元组：逐元素推断，产出 Type.TupleType
                val combined = Seq<Constraint>(0)
                val elementTypes = Seq<Type>(0)
                for (e in expr.elements) {
                    val r = inferExpr(e)
                    combined.addAll(r.constraints)
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
                combined.addAll(l.constraints)
                combined.addAll(r.constraints)

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
                    combined.addAll(r.constraints)
                    // 使用�?元素实际类型，声明方=统一的元素类型变�?
                    combined.add(Constraint.Equal(r.type, elemVar, e.span))
                }
                InferResult(Type.Arr(elemVar), combined)
            }

            is Expr.Index -> {
                // 索引：list 必须�?Array<result>，index 必须�?Int
                val l = inferExpr(expr.list)
                val index = inferExpr(expr.index)
                val combined = Seq<Constraint>(0)
                combined.addAll(l.constraints)
                combined.addAll(index.constraints)
                val elemVar = solver.freshVar()
                combined.add(Constraint.Equal(l.type, Type.Arr(elemVar), expr.list.span))
                combined.add(Constraint.Equal(index.type, BuiltinType.Int, expr.index.span))
                InferResult(elemVar, combined)
            }

            is Expr.Range -> {
                val combined = Seq<Constraint>(0)
                expr.left?.let { combined.addAll(inferExpr(it).constraints) }
                expr.right?.let { combined.addAll(inferExpr(it).constraints) }
                InferResult(BuiltinType.Unknown, combined)
            }

            is Expr.Call -> {
                val callee = inferExpr(expr.callee)
                val combined = Seq<Constraint>(0)
                combined.addAll(callee.constraints)
                // 类型实参挂在 callee �?Identifier 上（`foo<Int>(...)`），�?inferExpr(Identifier)
                // 按调用点实例化；Expr.Call.typeArgs 当前恒为 null，若未来解析器改为填充它�?
                // 在此合并�?callee 的实参上（TODO）�?

                // 实参类型：逐个推断
                val argTypes = Seq<Type>(expr.args.size)
                for (a in expr.args) {
                    val ar = inferExpr(a)
                    combined.addAll(ar.constraints)
                    argTypes.add(ar.type)
                }

                // �?callee 是已知具名函数：取「函数名位置 + 形参声明位置列表」用于报错定�?
                val calleeInfo = paramDeclSpansOf(expr.callee)

                // 1) 结构链接：callee 必须是「实参数量对应的函数类型」�?
                //    每个形参�?fresh 变量占位，等待与实参逐一约束�?
                //    t1=调用处合成的函数类型（使用方/实际），t2=函数声明类型（声明方/期望）�?
                val paramVars = Seq<Type>(argTypes.size)
                repeat(argTypes.size) { paramVars.add(solver.freshVar()) }
                val resVar = solver.freshVar()
                val fnType = Type.Func(paramVars, resVar)
                combined.add(Constraint.Equal(fnType, callee.type, expr.callee.span, calleeInfo?.first))

                // 2) 逐实参约束：使用�?实参自身 span（label），声明�?形参注解位置（label）�?
                //    这样每个不匹配的实参单独报错，而不是整�?Call 一个错误�?
                val paramDeclSpans = calleeInfo?.second
                val declSpanCount = paramDeclSpans?.size ?: 0
                for ((i, element) in argTypes.withIndex()) {
                    // 实参数量可能超过形参声明数量（结构链接约束会另行报「参数数量不匹配」）�?
                    // 越界的实参没有对应形参声明位�?�?declSpan �?null（退化为仅使用方 label）�?
                    // （paramDeclSpans �?null �?declSpanCount=0，`i < 0` 恒假�?. 保证不越界）
                    val declSpan = if (i < declSpanCount) paramDeclSpans?.get(i) else null
                    combined.add(
                        Constraint.Equal(
                            element,
                            paramVars[i],
                            expr.args[i].span,
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

    // 根据操作符和操作数类型确定结果类�?
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
     * 形参/返回值类型注�?�?[Type]�?
     *
     * 职责边界（Resolver / TypeSolver 分工）：
     * - Resolver 只做「类型名 �?[DefId]」的名称解析（填�?[Expr.Identifier.defId]），
     *   本方�?*不做任何名字解析**，只�?[SymbolTable] 完成「注解表达式 �?Type」的转换�?
     * - 生成�?[Constraint.Equal] �?[TypeSolver] 统一求解�?
     *
     * 当前限制：注解语法是匿名枚举（多个枚举值，�?`Int | Str`、`?(Num Str)`），
     * 而类型系统尚未引入联�?枚举类型，因此：
     * - 单一枚举值（`a: Int`、`r: (Num, Str)`）→ 正常转换�?[Type]�?
     * - 多个枚举�?�?报「暂不支持」错误并返回 [Type.Error]（抑制级联错误）�?
     *
     * @param annotation 形参/返回值的 `Expr.Annotation` 节点（内部注解即枚举值列表）
     * @return 注解对应的类型；无注�?无法转换时返回相应占位类�?
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
     * 单个枚举值表达式 �?[Type]�?
     * - 标识符：�?[DefId] �?[SymbolTable] 得符号类型；支持嵌套泛型注解（`Array<Int>`、`Array<T>`），
     *   转换细节�?[typeArgToType]�?
     * - 元组：递归转换元素，产�?[Type.TupleType]�?
     * - 无法转换（defId 缺失 / 符号不存�?/ 其它表达式）：返�?[Type.Error]�?
     */
    private fun variantToType(expr: Expr): Type {
        return when (expr) {
            is Expr.Identifier -> typeArgToType(expr)

            is Expr.Tuple -> {
                val elements = Seq<Type>(0)
                for (e in expr.elements) elements.add(variantToType(e))
                Type.TupleType(elements)
            }

            else -> Type.Error
        }
    }

    /**
     * 类型实参表达�?�?[Type]（支持嵌套泛型：`Int`、`Array<Int>`、`Array<Array<T>>`）�?
     *
     * 裸标识符�?
     * - 类型参数（`T`）→ 其类型变量（�?[analyzeFnStmt] 写入符号）；
     * - 内置/已知类型（`Int`）→ 符号类型�?
     * - �?`Array` �?宽松视为 `Array<?>`（元素类型由后续约束推断，与旧行为一致）�?
     * - 解析失败（defId 缺失）→ [Type.Error]（Resolver 已报"未声明的类型�?）�?
     *
     * 嵌套应用 `Head<Args...>`�?
     * - 头部�?`Array` �?[Type.App]（实参数量必须是 1）；
     * - 头部为类型参�?�?高阶类型（kind `* -> *`），报错（TODO）；
     * - 其它 �?�?不接受类型实�?�?
     */
    private fun typeArgToType(expr: Expr.Identifier): Type {
        val symbol = expr.defId?.let { symbolTable.get(it) }
        val nestedArgs = expr.typeArgs
        if (nestedArgs != null && !nestedArgs.isEmpty) {
            // 嵌套应用 `Head<Args...>`
            if (symbol?.values?.get(Symbol.TYPE_PARAM_KEY) == true) {
                // TODO: 支持高阶类型（对类型参数应用类型实参 `T<U>`，kind `* -> *`）后移除
                error(bundle.format("diag.hkt-not-supported", expr.token.literal))
                    .label(expr, bundle.get("diag.hkt-not-supported.help"))
                return Type.Error
            }
            val argTypes = Seq<Type>(nestedArgs.size)
            for (a in nestedArgs) argTypes.add(typeArgToType(a))
            return if (symbol?.type == BuiltinType.Array) {
                if (nestedArgs.size != 1) {
                    error(bundle.format("diag.type-arg-count", BuiltinType.Array.name, 1, nestedArgs.size))
                        .label(expr, "")
                    Type.Error
                } else {
                    Type.App(BuiltinType.Array, argTypes)
                }
            } else {
                error(bundle.format("diag.type-not-generic", symbol?.name ?: expr.token.literal))
                    .label(expr, "")
                Type.Error
            }
        }
        // 裸标识符
        return when {
            symbol == null -> Type.Error
            symbol.values.get(Symbol.TYPE_PARAM_KEY) == true -> symbol.type
            symbol.type == BuiltinType.Array ->
                // �?`Array`：宽松视�?`Array<?>`
                // TODO: 严格模式（Rust 风格）下应报"`Array` 需�?1 个类型实�?
                Type.App(BuiltinType.Array, Seq.with(solver.freshVar()))
            else -> symbol.type
        }
    }

    /**
     * 按调用点实例化泛型函数的类型方案�?
     *
     * - 无显式实参：全部 fresh 变量（全推断）；
     * - 有显式实参：数量必须等于声明的类型参数数量（[diag.explicit-type-arg-count]），
     *   按序替换；数量不一致时报错并回退为全推断，继续编译�?
     *
     * @param declaredCount 声明的类型参数数量（区别�?[TypeScheme.typeVars] 的运行时大小—�?
     *   scheme 求解后可能并入额外的泛化变量，显式实参只对声明的参数计数�?
     */
    private fun instantiateScheme(symbol: Symbol, expr: Expr.Identifier, declaredCount: Int): Type {
        val explicitArgs = expr.typeArgs
        val argCount = explicitArgs?.size ?: 0
        if (argCount != 0 && argCount != declaredCount) {
            error(bundle.format("diag.explicit-type-arg-count", declaredCount, argCount))
                .label(expr, bundle.format("diag.explicit-type-arg-count.help", declaredCount))
            // 回退以继续编译：非泛型符�?�?返回原符号类型（占位 scheme 不能用作实际类型）；
            // 泛型函数 �?全推断（fresh 变量）�?
            return if (declaredCount == 0) {
                symbol.type
            } else {
                symbol.typeScheme.instantiateWith(Seq<Type>(0), { solver.freshVar() })
            }
        }
        if (argCount == 0) {
            return symbol.typeScheme.instantiateWith(Seq<Type>(0), { solver.freshVar() })
        }
        val argTypes = Seq<Type>(argCount)
        for (a in explicitArgs!!) argTypes.add(typeArgToType(a))
        return symbol.typeScheme.instantiateWith(argTypes, { solver.freshVar() })
    }

    /**
     * 当前泛型参数嵌套栈中所有类型参数索引的并集（泛化时排除外层函数已量化的变量）�?
     * 返回副本，随栈变化不受影响�?
     */
    private fun envFreeVars(): IntSet {
        val set = IntSet()
        for (frame in typeParamStack) {
            for (v in frame) set.add(v.index)
        }
        return set
    }

    /**
     * 合并声明的类型参数与泛化出的额外变量：声明的在前（保持声明顺序，显式实参按此计数），
     * 泛化出的新变量追加在后；按索引去重�?
     */
    private fun mergeTypeVars(declared: Seq<Type.Var>, generalized: Seq<Type.Var>): Seq<Type.Var> {
        val seen = ObjectMap<Int, Boolean>()
        val merged = Seq<Type.Var>(declared.size + generalized.size)
        for (v in declared) {
            if (!seen.containsKey(v.index)) {
                seen.put(v.index, true)
                merged.add(v)
            }
        }
        for (v in generalized) {
            if (!seen.containsKey(v.index)) {
                seen.put(v.index, true)
                merged.add(v)
            }
        }
        return merged
    }

    // �?`Identifier` �?`Annotation(Identifier, ...)` 中取出标识符
    private fun unwrapIdentifier(expr: Expr): Expr.Identifier? {
        return when (expr) {
            is Expr.Identifier -> expr
            is Expr.Annotation -> expr.expr as? Expr.Identifier
            else -> null
        }
    }

    /**
     * �?[calleeExpr] 是已知具名函数（Identifier 且符号类型为 [Type.Func]），
     * 返回 `函数名位置` �?`形参声明位置列表`（与形参类型变量一一对应），
     * 供调用处报错的声明方 label 定位�?
     *
     * 形参位置取自 [varDeclSpans]（形参类型变�?�?注解 span，在 [analyzeFnStmt] 登记）；
     * 无注解的形参没有「期望类型声明处」，对应位置�?null�?
     *
     * @return null 表示 callee 不是已知具名函数（如 lambda、方法引用）�?
     *         调用处报错将退化为只有使用�?label、没有声明方 label�?
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

    /**
     * 泛型函数登记信息：求解完成后基于已求解的 body 重建 TypeScheme�?
     *
     * @param declaredVars 声明处按序分配的类型参数变量（显式实参按此计�?替换�?
     * @param envFreeVars 泛化时需排除的外层类型参数索引（声明处的嵌套栈快照）
     */
    private class GenericFnInfo(
        val symbol: Symbol,
        val declaredVars: Seq<Type.Var>,
        val envFreeVars: IntSet,
    )

    // endregion
}