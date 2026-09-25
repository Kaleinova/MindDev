package mlogix.compiler.passes.typing

import arc.struct.IntSet
import arc.struct.ObjectMap
import arc.struct.Seq
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.symbol.DefId
import mlogix.compiler.core.symbol.Symbol
import mlogix.compiler.core.symbol.SymbolTable
import mlogix.compiler.core.symbol.VariantPayload
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.core.type.*
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.diagnostic.Diagnostic.SemanticDiag
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
class TypeInferencer(val context: CompilerContext) {
    private lateinit var sourceFile: SourceFile
    private lateinit var symbolTable: SymbolTable
    private lateinit var solver: TypeSolver
    private val constraints = Seq<Constraint>()

    /** 当前函数返回上下文栈：期望返回类型 + 函数声明位置（用于 return 不匹配报错的声明方 label） */
    private val returnContextStack = Seq<ReturnContext>(2)

    /** 类型变量 → 声明位置：形参类型变量登记其注解 span，供调用处报错的声明方 label 使用 */
    private val varDeclSpans = ObjectMap<Int, Span>()

    /**
     * 函数定义 → 形参声明信息（与形参**下标**一一对应）。
     *
     * 两处用途：
     * - 双向检查：把「注解类型 + 其来源」作为 [ExpectedType] 下推给实参（错误就地报在实参上）；
     * - 报错定位：类型不匹配时给出声明方 label（可下钻到 `Option<Int>` 的 `Int`）。
     *
     * 与 [varDeclSpans] 的区别：后者按类型变量索引登记（只有形参类型恰好是变量时才查得到），
     * 这里按下标登记，泛型函数里 `x: Option<T>` 这类非变量形参也能定位。
     */
    private val TypeAnnotations = ObjectMap<DefId, Seq<TypeAnnotation>>()

    /** 泛型函数定义 → 它声明的类型参数变量：下推期望类型时要避开（跨调用点共享，下推会互相污染） */
    private val fnTypeParamVars = ObjectMap<DefId, Seq<Type.Var>>()

    /**
     * 泛型定义（枚举 / 泛型函数）→ 声明的类型参数名。
     * 供「显式类型实参数量不匹配」的 note 说明数量从哪来（`enum Option<T>` / `fn id<T>`）。
     */
    private val declaredTypeParamNames = ObjectMap<DefId, Seq<String>>()

    /** 泛型函数登记表：walk 阶段登记，求解完成后统一重建 TypeScheme（见 [analyze] 末尾） */
    private val genericFns = Seq<GenericFnInfo>(4)

    /** 泛型参数嵌套栈：每帧 = 当前函数声明的类型参数变量；用于泛化时排除外层函数的类型参数 */
    private val typeParamStack = Seq<Seq<Type.Var>>(2)

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
        solver = TypeSolver(context.diagHandler, sourceFile)
        constraints.clear()
        genericFns.clear()
        typeParamStack.clear()
        TypeAnnotations.clear()
        fnTypeParamVars.clear()
        declaredTypeParamNames.clear()

        // walk AST and collect constraints
        analyzeStmt(result.ast)

        // solve collected constraints
        solver.solveEqualities(constraints)

        // 泛型函数：求解完成后，基于"已求解的 body"重新泛化。
        // 为什么需要这步：类型方案在 walk 阶段（函数体分析前）暂挂，此时求解器尚未合并任何约束；
        // 若直接使用暂挂的 body（如 `fn f<T>(x: T) { return x }` 的 Func([T], resultVar)），
        // 结果变量 resultVar 未量化、会被所有调用点共享，导致第二次调用类型冲突。
        // 求解后 body 中的 resultVar 已折叠为类型参数 T，泛化只量化真正自由的变量。
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
                // 形参等直接挂类型变量的符号：求解后把具体类型写回（如 `a: Int` → Con("Int")）。
                // 类型参数符号除外——它的类型必须保持为量化变量，供注解 `x: T` 引用。
                val final = solver.read(symbol.type)
                if (final !is Type.Var) {
                    symbol.type = final
                    symbol.values.put("final", final)
                }
            }
        }
    }

    /**
     * `set` 声明：推断初值、做符号类型快速传播，并把**类型注解**接进约束。
     *
     * 此前注解既不转类型也不参与检查（见旧 TODO），于是 `set a : Int = "s"`、
     * `set a : Option<Int> = Option.Some("s")`、`set a : Array<Int> = {"x"}` 全部静默通过。
     * 现在注解就是变量的声明方类型：
     * - `set a : T = 初值` → 期望类型 `T` 下推给初值表达式（双向检查），
     *   错误就地报在写出错误值的子表达式上（`{"x"}` 里报 `"x"`）；
     * - `set a : T`（无初值）→ 变量类型即 T，后续 `a = ...` 按 T 检查。
     *
     * 注解只支持单一枚举值；`set a : Int | Str` 这类多枚举值仍由 [annotationToType] 报 union-not-supported。
     */
    private fun analyzeSetVarStmt(stmt: Stmt.SetVarStmt) {
        val annotation = stmt.`var` as? Expr.Annotation
        val declared = annotation?.let { TypeAnnotation(annotationToType(it), annotationToOrigin(it)) }
        val symbol = unwrapIdentifier(stmt.`var`)?.defId?.let { symbolTable.get(it) }

        val assign = stmt.assignStmt
        if (assign == null) {
            // `set a`：符号保持 Unknown；有注解则以注解为准
            if (declared != null && symbol != null) symbol.type = declared.type
            return
        }

        // 与 AssignStmt 相同的推断，但每个表达式只推断一次并做符号类型快速传播：
        // 递归 analyzeStmt(assignStmt) 会让同一 RHS 被推断两次、其上的错误重复上报。
        val lr = inferExpr(assign.`var`)
        constraints.addAll(lr.constraints)
        val valueR = inferExpr(assign.value, declared?.let { ExpectedType(it.type, it.origin) })
        constraints.addAll(valueR.constraints)

        if (symbol == null) return
        if (declared != null) {
            // 初值已就地消费期望类型（字面量/数组字面量/变体构造器）时跳过，避免重复报错。
            // 使用方=初值类型（label 在初值处），声明方=注解类型（label 在注解处，可下钻到 `Int`）。
            if (!valueR.expectedHandled) {
                constraints.add(
                    Constraint.Equal(
                        valueR.type,
                        declared.type,
                        assign.value.span,
                        declared.origin?.span,
                        valueR.origin,
                        declared.origin,
                    )
                )
            }
            // 变量类型以注解为准，后续语句直接按注解类型检查
            symbol.type = declared.type
        } else if (valueR.type != BuiltinType.Unknown) {
            // 快速传播：后续语句读取该变量时能直接看到（推断出的）类型
            // （如 `set a = 1; set b = a` 中 b 能看到 a 已是 Int）
            symbol.type = valueR.type
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
                // flag 是循环标签，不推断
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
                if (returnContextStack.isEmpty) {
                    stmt.expr?.let { val r = inferExpr(it); constraints.addAll(r.constraints) }
                    return
                }
                val context = returnContextStack.peek()
                // 返回类型可下推时（注解不含本函数自己的类型参数）把期望类型交给返回值推断，
                // 错误就地报在返回表达式上（如 `return Option.Some("s")` 报在 `"s"`）
                val exprR = stmt.expr?.let { inferExpr(it, context.push) }
                exprR?.let { constraints.addAll(it.constraints) }
                if (exprR == null || !exprR.expectedHandled) {
                    val returnType = exprR?.type ?: BuiltinType.Null
                    // t1=实际返回类型(使用方)，t2=函数声明返回类型(声明方)，
                    // declPos=返回类型注解处（无注解时为函数名位置）
                    constraints.add(
                        Constraint.Equal(
                            returnType,
                            context.expected,
                            stmt.span,
                            context.declSpan,
                            exprR?.origin,
                            context.expectedOrigin,
                        )
                    )
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
                            // 使用方=实际 RHS 类型（label 于 RHS 处），声明方=变量声明类型（label 于符号声明处）
                            constraints.add(Constraint.Equal(valueR.type, leftType, stmt.value.span, symbol.span))
                        }
                    }
                }
                // TODO non-identifier LHS (e.g. indexing, field access): subexpressions already analyzed above.
            }

            is Stmt.SetVarStmt -> {
                analyzeSetVarStmt(stmt)
            }

            is Stmt.EnumStmt -> {
                analyzeEnumStmt(stmt)
            }

            else -> {
                // unhandled statement kinds
            }
        }
    }

    /**
     * 函数声明：为函数符号构造 [Type.Func]，绑定形参类型，分析函数体。
     *
     * 泛型形参（`fn foo<T, E>`）：
     * - 每个类型参数分配一个 fresh 类型变量，写入类型参数符号（注解 `x: T` 经符号查得该变量）；
     * - **泛型函数签名直接使用注解类型**（`x: T` → 形参类型就是 T 的变量），不引入中间变量，
     *   保证按调用点实例化时 T 被正确替换、不跨调用点共享；
     * - 暂挂 TypeScheme 时把签名中全部自由变量（类型参数 + 未注解形参/返回值变量）一并量化，
     *   供函数体递归调用实例化；求解完成后在 [analyze] 末尾基于已求解的 body 重新泛化。
     *
     * 非泛型函数维持既有行为：形参为 fresh 变量 + Equal(注解) 约束（调用处声明方 label 定位依赖它）。
     *
     * 形参/返回值类型注解的职责分工：
     * - Resolver 已把注解中的类型名解析为 [DefId]（填在 [Expr.Identifier.defId]）；
     * - 本方法只做「已解析注解表达式 → [Type]」的转换（[annotationToType]）；
     * - [TypeSolver] 只负责求解，不做名字解析、不做类型构造。
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

        // 泛型形参：每个类型参数分配 fresh 变量并写入类型参数符号。
        // 声明处嵌套 `E<U>`（高阶类型）已在 Resolver 报错，这里按声明顺序只绑定头部名字。
        val typeParamVars = Seq<Type.Var>(typeParams?.size ?: 0)
        if (typeParams != null && isGeneric) {
            // 登记类型参数名，供「显式类型实参数量不匹配」的 note 指向声明处
            declaredTypeParamNames.put(fnSymbol.id, typeParamNamesOf(typeParams))
            for (typeParam in typeParams) {
                val v = solver.freshVar()
                typeParamVars.add(v)
                // 类型参数自身的声明位置也登记，供调用处报错的声明方 label 使用
                varDeclSpans.put(v.index, typeParam.span)
                typeParam.defId?.let { defId ->
                    symbolTable.get(defId)?.let { paramSymbol -> paramSymbol.type = v }
                }
            }
        }

        // 形参类型（形参声明信息与下标一一对应：注解类型 + 来源，供期望类型下推与声明方 label）
        val paramTypes = Seq<Type>(8)
        val TypeAnnotationInfo = Seq<TypeAnnotation>(stmt.params?.size ?: 0)
        stmt.params?.let { params ->
            for (p in params) {
                if (p is Expr.Annotation) {
                    val declType = annotationToType(p)
                    TypeAnnotationInfo.add(TypeAnnotation(declType, annotationToOrigin(p)))
                    if (isGeneric) {
                        // 泛型函数：注解类型直接作为形参类型（`x: T` → T 的变量）
                        paramTypes.add(declType)
                        registerVarSpan(declType, p.span)
                    } else {
                        // 非泛型：tv + Equal(变量, 注解类型) 约束（维持既有行为）
                        val tv = solver.freshVar()
                        paramTypes.add(tv)
                        val useSpan = unwrapIdentifier(p)?.span ?: p.span
                        // 登记：该类型变量的声明位置 = 形参注解整体 span（调用处报错的声明方 label 用）
                        varDeclSpans.put(tv.index, p.span)
                        // t1=形参实际类型(使用方)，t2=注解声明的类型(声明方)
                        constraints.add(Constraint.Equal(tv, declType, useSpan, p.span))
                    }
                } else {
                    // 无注解：fresh 变量（泛型函数中它会并入暂挂 scheme 的 typeVars）
                    paramTypes.add(solver.freshVar())
                    TypeAnnotationInfo.add(TypeAnnotation.None)
                }
            }
        }
        // 形参声明按下标登记（不像 varDeclSpans 那样按类型变量索引：泛型函数的形参类型可能不是变量）
        if (!TypeAnnotationInfo.isEmpty) TypeAnnotations.put(fnSymbol.id, TypeAnnotationInfo)
        if (isGeneric) fnTypeParamVars.put(fnSymbol.id, typeParamVars)

        // 返回值类型：无注解 → fresh 变量；单一返回值有注解 → 注解类型（泛型）或 Equal 约束（非泛型）。
        // 多返回值（`-> a: T1, b: T2`）尚未建模（Type.Func 只有单一 result），暂不约束。
        var resultType: Type = solver.freshVar()
        var resultOrigin: TypeOrigin? = null
        // 返回值也可下推期望类型（`return Option.Some("s")` 对 `-> r : Option<Int>`），
        // 但注解含本函数自己的类型参数时不下推（那些变量跨调用点共享）
        var resultPush: ExpectedType? = null
        // 返回类型不匹配时的声明方 label：有注解就指注解本身（`-> r : Int` 的 `Int`），
        // 否则退回函数名位置（那里是函数的声明处）
        var resultDeclSpan: Span = stmt.name?.span ?: stmt.span
        stmt.results?.let { results ->
            if (results.size == 1) {
                val result = results[0]
                if (result is Expr.Annotation) {
                    val declType = annotationToType(result)
                    resultOrigin = annotationToOrigin(result)
                    resultDeclSpan = result.span
                    if (!isGeneric || !mentionsAnyVar(declType, typeParamVars)) {
                        resultPush = ExpectedType(declType, resultOrigin)
                    }
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

        // 泛型函数：暂挂 scheme（供递归调用实例化），并登记到 genericFns，
        // 求解完成后统一重建为"已泛化的 scheme"（见 analyze() 末尾）。
        if (isGeneric) {
            val quantified = mergeTypeVars(typeParamVars, signatureFreeVars(fnSymbol.type, typeParamVars))
            fnSymbol.typeScheme = TypeScheme(quantified, fnSymbol.type)
            fnSymbol.values.put(Symbol.TYPE_PARAM_COUNT_KEY, typeParamVars.size)
            genericFns.add(GenericFnInfo(fnSymbol, typeParamVars, envFreeVars()))
            typeParamStack.add(typeParamVars)
        }

        // analyze body with parameters bound
        returnContextStack.add(ReturnContext(resultType, resultDeclSpan, resultOrigin, resultPush))
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
     * 若 [type] 是类型变量，把其声明位置登记到 [varDeclSpans]（调用处报错的声明方 label 用）。
     */
    private fun registerVarSpan(type: Type, span: Span) {
        if (type is Type.Var) varDeclSpans.put(type.index, span)
    }

    /**
     * 枚举声明：为枚举类型分配类型参数变量，并把每个变体登记为「构造器」类型方案。
     *
     * - 单元变体 `Red` → 类型就是枚举类型（`Color`），它本身就是值；
     * - 元组/结构体变体 `Rgb(Num, Num, Num)` → `(Num, Num, Num) -> Color`，
     *   于是 `Color.Rgb` 是函数值、`Color.Rgb(1.0, 2.0, 3.0)` 是 `Color` 值；
     * - 类型方案 `∀T. (T) -> Option<T>` 让每个访问点实例化出独立的类型变量（多态，
     *   与泛型函数同机制），因此 `Option.Some(1)` 与 `Option.Some("s")` 互不干扰。
     */
    private fun analyzeEnumStmt(stmt: Stmt.EnumStmt) {
        val enumSymbol = stmt.defId?.let { symbolTable.get(it) } ?: return

        // 泛型形参：每个类型参数分配 fresh 变量并写入类型参数符号（与泛型函数一致）
        val typeParams = stmt.typeParams
        val typeParamVars = Seq<Type.Var>(typeParams?.size ?: 0)
        if (typeParams != null) {
            // 登记类型参数名，供「显式类型实参数量不匹配」的 note 指向声明处
            declaredTypeParamNames.put(enumSymbol.id, typeParamNamesOf(typeParams))
            for (typeParam in typeParams) {
                val v = solver.freshVar()
                typeParamVars.add(v)
                varDeclSpans.put(v.index, typeParam.span)
                typeParam.defId?.let { defId ->
                    symbolTable.get(defId)?.let { paramSymbol -> paramSymbol.type = v }
                }
            }
        }

        val enumType: Type
        if (typeParamVars.isEmpty) {
            enumType = Type.Con(enumSymbol.name)
        } else {
            val args = Seq<Type>(typeParamVars.size)
            for (v in typeParamVars) args.add(v)
            enumType = Type.App(Type.Con(enumSymbol.name), args)
        }
        enumSymbol.type = enumType

        for (variant in stmt.variants) {
            val variantSymbol = variant.name.defId?.let { symbolTable.get(it) } ?: continue
            val fields = stmt.fieldsOf(variant)
            val payloadTypes = Seq<Type>(fields.size)
            val payloadNames = Seq<String>(fields.size)
            val payloadOrigins = Seq<TypeOrigin>(fields.size)
            for (field in fields) {
                payloadTypes.add(variantFieldToType(field))
                payloadNames.add(variantFieldNameOf(field))
                payloadOrigins.add(variantFieldOrigin(field))
            }

            val variantType: Type =
                if (payloadTypes.isEmpty) enumType else Type.Func(payloadTypes, enumType)

            variantSymbol.type = variantType
            // 类型方案：量化的类型参数在每个访问点实例化为 fresh 变量
            variantSymbol.typeScheme = TypeScheme(typeParamVars, variantType)
            variantSymbol.values.put(Symbol.VARIANT_PAYLOAD_KEY, VariantPayload(payloadNames, payloadOrigins))
        }
    }

    /**
     * 标识符推断（不含期望类型消费；消费由 [inferExpr] 统一包装）。
     *
     * 变量的来源就是它的使用位置：作为「使用方」下钻到最里层时，label 指向这里的引用。
     */
    private fun inferIdentifier(expr: Expr.Identifier): InferResult {
        val origin = TypeOrigin(expr.span)
        val defId = expr.defId
        if (defId == null) {
            // Resolver 已报 diag.undeclared-identifier（名称解析归它管），
            // 这里静默降级为 Error，避免同一错误重复上报。
            return InferResult(BuiltinType.Error, Seq(0), origin)
        }
        val symbol = symbolTable.get(defId) ?: return InferResult(BuiltinType.Unknown, Seq(0), origin)
        if (symbol.values.get(Symbol.TYPE_PARAM_KEY) == true) {
            // 类型参数只能出现在类型位置（注解/类型实参），不能作为值使用
            error(bundle.format("diag.type-param-as-value", symbol.name))
                .label(expr, "")
            return InferResult(BuiltinType.Error, Seq(0), origin)
        }
        if (symbol.values.get(Symbol.ENUM_KEY) == true) {
            // 枚举类型名只能用来访问变体（`Color.Red`），不能当值；变体的类型就是枚举类型
            error(bundle.format("diag.enum-as-value", symbol.name))
                .label(expr, bundle.format("diag.enum-as-value.help", symbol.name))
            return InferResult(BuiltinType.Error, Seq(0), origin)
        }
        // 泛型函数（或值位置携带显式类型实参）：按调用点实例化类型方案，
        // 每次引用得到独立的类型变量（多态）。
        val explicitArgs = expr.typeArgs
        val hasExplicit = explicitArgs != null && !explicitArgs.isEmpty
        val declaredCount = symbol.values.get(Symbol.TYPE_PARAM_COUNT_KEY) as? Int ?: 0
        if (!symbol.typeScheme.typeVars.isEmpty || declaredCount != 0 || hasExplicit) {
            return InferResult(instantiateScheme(symbol, expr, declaredCount), Seq(0), origin)
        }
        var ty = symbol.type
        if (ty == BuiltinType.Unknown) {
            // create type variable to be inferred
            ty = solver.freshVar()
            symbol.values.put("inferred", ty)
        }
        return InferResult(ty, Seq(0), origin)
    }

    /**
     * 叶子表达式就地消费期望类型：加一条「实际 = 期望」的约束（双方都带来源，便于收窄 label），
     * 并标记 [InferResult.expectedHandled]，调用方不再重复加约束。
     */
    private fun withExpectedCheck(result: InferResult, expected: ExpectedType, span: Span): InferResult {
        val combined = Seq<Constraint>(result.constraints.size + 1)
        combined.addAll(result.constraints)
        combined.add(
            Constraint.Equal(result.type, expected.type, span, expected.span, result.origin, expected.origin)
        )
        return InferResult(result.type, combined, result.origin, true)
    }

    /** 期望类型是数组时的元素期望（`Array<T>` / `Arr(T)`）；不是数组则为 null */
    private fun expectedElementOf(expected: ExpectedType): ExpectedType? = when (val type = expected.type) {
        is Type.Arr -> ExpectedType(type.element, expected.origin?.childAt(0))
        is Type.App ->
            if (type.con == BuiltinType.Array && type.args.size == 1) {
                ExpectedType(type.args.get(0), expected.origin?.childAt(0))
            } else {
                null
            }

        else -> null
    }

    /**
     * 期望类型能否安全下推给被调用方的实参：注解类型已知，且**不含**被调用函数自己量化的类型参数
     * （那些变量跨调用点共享，下推会让不同调用互相污染；此时退回原有的约束式检查）。
     */
    private fun pushableExpected(decl: TypeAnnotation?, calleeDefId: DefId?): ExpectedType? {
        if (decl == null || !decl.isPresent) return null
        val typeParams = calleeDefId?.let { fnTypeParamVars.get(it) }
        if (typeParams != null && mentionsAnyVar(decl.type, typeParams)) return null
        return ExpectedType(decl.type, decl.origin)
    }

    /** [type] 中是否出现 [vars] 里的任一类型变量 */
    private fun mentionsAnyVar(type: Type, vars: Seq<Type.Var>): Boolean {
        if (vars.isEmpty) return false
        val wanted = ObjectMap<Int, Boolean>()
        for (v in vars) wanted.put(v.index, true)
        var found = false
        type.accept(object : TypeVisitor {
            override fun visitVar(type: Type.Var) {
                if (wanted.containsKey(type.index)) found = true
            }
        })
        return found
    }


    /**
     * 变体载荷字段的类型来源（与 [variantFieldToType] 同构）：
     * 结构体变体取冒号后类型表达式的来源（`height: Num` → `Num`），元组变体取类型表达式本身。
     */
    private fun variantFieldOrigin(field: Expr): TypeOrigin = when (field) {
        is Expr.Annotation -> annotationToOrigin(field)
        else -> variantToOrigin(field)
    }

    /**
     * 变体载荷字段 → [Type]：
     * - 结构体变体字段是 `名称 : 类型` 注解 → [annotationToType]；
     * - 元组变体字段本身就是类型表达式 → [typeArgToType]。
     */
    private fun variantFieldToType(field: Expr): Type = when (field) {
        is Expr.Annotation -> annotationToType(field)
        is Expr.Identifier -> typeArgToType(field)
        is Expr.Tuple -> {
            val elements = Seq<Type>(field.elements.size)
            for (element in field.elements) elements.add(variantFieldToType(element))
            Type.TupleType(elements)
        }

        else -> Type.Error
    }

    /** 变体载荷字段名（仅诊断用）；元组变体字段没有名字 */
    private fun variantFieldNameOf(field: Expr): String = when (field) {
        is Expr.Annotation -> (field.expr as? Expr.Identifier)?.let { identifierNameOf(it) } ?: ""
        is Expr.Identifier -> identifierNameOf(field)
        else -> ""
    }

    private fun identifierNameOf(identifier: Expr.Identifier): String =
        (identifier.token.literal as? String) ?: identifier.token.type.toString()

    /** 声明的类型参数名（`enum Option<T, E>` → `[T, E]`），供诊断说明声明处 */
    private fun typeParamNamesOf(typeParams: Seq<Expr.Identifier>): Seq<String> {
        val names = Seq<String>(typeParams.size)
        for (typeParam in typeParams) names.add(identifierNameOf(typeParam))
        return names
    }

    /**
     * 若 [expr] 是「枚举名.变体」访问（`field` 的 [DefId] 由 Resolver 填为变体符号），返回变体符号。
     */
    private fun enumVariantSymbolOf(expr: Expr?): Symbol? {
        val get = expr as? Expr.Get ?: return null
        val fieldIdent = get.field as? Expr.Identifier ?: return null
        val symbol = fieldIdent.defId?.let { symbolTable.get(it) } ?: return null
        return if (symbol.values.get(Symbol.ENUM_VARIANT_KEY) == true) symbol else null
    }

    /** [expr] 是解析到枚举类型符号的标识符时返回该符号 */
    private fun enumTypeSymbolOf(expr: Expr?): Symbol? {
        val ident = expr as? Expr.Identifier ?: return null
        val symbol = ident.defId?.let { symbolTable.get(it) } ?: return null
        return if (symbol.values.get(Symbol.ENUM_KEY) == true) symbol else null
    }

    /**
     * 按访问点实例化变体构造器：类型实参来自 `枚举名<T, ...>.变体`。
     * 数量不符时复用 [diag.explicit-type-arg-count] 并回退为全推断（继续编译）。
     */
    private fun instantiateVariant(variantSymbol: Symbol, at: Expr, explicitArgs: Seq<Expr.Identifier>?): Type {
        val declaredCount = variantSymbol.typeScheme.typeVars.size
        val argCount = explicitArgs?.size ?: 0
        if (argCount != 0 && argCount != declaredCount) {
            // label 只圈住写出类型实参的那一段（`Option<Int, Str>`），
            // 而不是整个变体访问（`Option<Int, Str>.Some`）
            val typeArgSite: Expr = (at as? Expr.Get)?.obj ?: at
            val ownerDefId = (typeArgSite as? Expr.Identifier)?.defId
            val diagnostic = error(bundle.format("diag.explicit-type-arg-count", declaredCount, argCount))
                .label(typeArgSite, bundle.format("diag.explicit-type-arg-count.help", declaredCount))
            noteTypeParamSource(diagnostic, ownerDefId, declaredCount)
            labelExtraTypeArgs(diagnostic, explicitArgs, declaredCount)
            return variantSymbol.typeScheme.instantiateWith(Seq<Type>(0)) { solver.freshVar() }
        }
        if (argCount == 0) {
            return variantSymbol.typeScheme.instantiateWith(Seq<Type>(0)) { solver.freshVar() }
        }
        val argTypes = Seq<Type>(argCount)
        for (arg in explicitArgs!!) argTypes.add(typeArgToType(arg))
        return variantSymbol.typeScheme.instantiateWith(argTypes) { solver.freshVar() }
    }

    /**
     * 给「显式类型实参数量不匹配」补上**逐个多余实参**的修复建议
     * （对应 rustc E0107 的 `help: remove this generic argument`）：
     * 一条 `help` 带若干删除 label，渲染成可照着改的 `-` 标记。
     *
     * 不额外挂"多余实参"次级 label：它必然落在主 label（整个类型表达式）区间内，
     * 而渲染器会丢弃同一行重叠的 label（见 [mlogix.compiler.diagnostic.Diagnostic.renderLine]），
     * 徒增噪音；指向具体实参的职责由 help 的删除标记承担。
     *
     * 实参不足时**不**给代码建议：本语言没有 `_` 类型占位符，插入任何写法都会引入新错误，
     * 只在主 label 的文案里提示「传恰好 N 个，或不传以全部推断」。
     */
    private fun labelExtraTypeArgs(
        diagnostic: Diagnostic,
        explicitArgs: Seq<Expr.Identifier>?,
        declaredCount: Int,
    ) {
        if (explicitArgs == null) return
        val extras = Seq<Expr.Identifier>(0)
        for ((i, arg) in explicitArgs.withIndex()) {
            if (i >= declaredCount) extras.add(arg)
        }
        if (extras.isEmpty) return

        val help = diagnostic.help(bundle.format("diag.explicit-type-arg-count.remove-extra", extras.size))
        for (extra in extras) help.delete(extra)
    }

    /**
     * 给「显式类型实参数量不匹配」补一条 note：泛型定义在哪儿、声明了几个（哪些）类型参数。
     *
     * 对应 rustc 的 `note: struct defined here, with 1 generic parameter: T`：
     * note 自带 label，渲染成指向声明处的代码片段。
     *
     * @param ownerDefId 泛型定义的 [DefId]（枚举 / 泛型函数）；拿不到来源时不加 note
     */
    private fun noteTypeParamSource(diagnostic: Diagnostic, ownerDefId: DefId?, declaredCount: Int) {
        val owner = ownerDefId?.let { symbolTable.get(it) } ?: return
        val names = declaredTypeParamNames.get(owner.id) ?: return
        val namesText = names.joinToString(", ") { "`$it`" }
        diagnostic
            .note(bundle.format("diag.explicit-type-arg-count.note", owner.name, declaredCount, namesText))
            .label(owner.span, "")
    }

    /**
     * `枚举名.变体(实参...)`：按变体载荷检查实参并产出枚举类型。
     *
     * 与普通调用不同：载荷类型就是构造器形参，不需要「结构链接」再逐实参约束，
     * 因此数量不符能给出变体专属报错，类型不符能带上字段声明方位置（声明方 label）。
     *
     * 双向检查：若 [expected] 与本变体同属一个枚举（`Option<Int>` 对 `Option.Some(...)`），
     * 则**采用期望类型作为结果**，并把期望的类型实参按「直接出现」的映射下推到对应载荷实参，
     * 于是 `Option.Some("s")` 对 `Option<Int>` 的错误就报在 `"s"` 上（不再依赖事后收窄）。
     */
    private fun inferEnumVariantCall(
        call: Expr.Call,
        variantSymbol: Symbol,
        expected: ExpectedType?,
    ): InferResult {
        val explicitArgs = (call.callee as? Expr.Get)?.let { (it.obj as? Expr.Identifier)?.typeArgs }
        val constructorType = instantiateVariant(variantSymbol, call.callee, explicitArgs)
        val payloadTypes = (constructorType as? Type.Func)?.params ?: Seq<Type>(0)
        val enumType = (constructorType as? Type.Func)?.result ?: constructorType
        val payload = variantSymbol.values.get(Symbol.VARIANT_PAYLOAD_KEY) as? VariantPayload

        val enumApp = enumType as? Type.App
        val expectedApp = expected?.type as? Type.App
        // 期望类型与本变体同属一个枚举、且类型实参数量一致时：采用期望作为结果，并把期望的类型实参下推
        val adoptedTypeArgs: Seq<Type>? =
            if (enumApp != null && expectedApp != null &&
                expectedApp.con == enumApp.con && expectedApp.args.size == enumApp.args.size
            ) {
                expectedApp.args
            } else {
                null
            }
        val resultType = adoptedTypeArgs?.let { expected?.type ?: enumType } ?: enumType

        val combined = Seq<Constraint>(0)
        if (payloadTypes.size != call.args.size) {
            error(
                bundle.format(
                    "diag.enum-variant-field-count",
                    variantSymbol.name,
                    payloadTypes.size,
                    call.args.size,
                )
            ).label(
                call,
                bundle.format("diag.enum-variant-fields", variantSymbol.name, payload?.namesText() ?: ""),
            )
        }

        val argOrigins = Seq<TypeOrigin>(call.args.size)
        for ((i, arg) in call.args.withIndex()) {
            // 载荷位 → 对应的枚举类型实参（直接出现）→ 该位实参的期望类型
            val pushed = if (adoptedTypeArgs != null && enumApp != null) {
                directPayloadExpectation(payloadTypes, i, enumApp.args, adoptedTypeArgs, expected?.origin)
            } else {
                null
            }
            val r = inferExpr(arg, pushed)
            combined.addAll(r.constraints)
            // 下标对齐地收集实参来源，供构造结果的来源树把类型实参映射回实参表达式
            argOrigins.add(r.origin ?: TypeOrigin.Unknown)
            // 实参内部已消费期望时不再重复加约束（那次比较已在实参里完成）
            if (i < payloadTypes.size && !r.expectedHandled) {
                // 有期望可下推时按**期望类型**比较（否则实参没消费期望就会漏检，见下方 adopted 说明）
                val declType = pushed?.type ?: payloadTypes.get(i)
                val declSpan = pushed?.span ?: payload?.spanOf(i)
                val declOrigin = pushed?.origin ?: payload?.originAt(i)
                combined.add(Constraint.Equal(r.type, declType, arg.span, declSpan, r.origin, declOrigin))
            }
        }

        // 采用期望类型后，枚举自身的类型实参也要与期望对齐：结果类型已经**就是**期望类型，
        // 调用方不会再比较一次，所以「未下推」的位置（如 `Cons(T, List<T>)` 里的 T、
        // 或载荷类型不含该实参的变体）必须在这里兜底，否则这些位置的不匹配会漏报。
        if (adoptedTypeArgs != null && enumApp != null) {
            for ((j, typeArg) in enumApp.args.withIndex()) {
                val expectedOrigin = expected?.origin?.childAt(j)
                combined.add(
                    Constraint.Equal(
                        typeArg,
                        adoptedTypeArgs.get(j),
                        call.span,
                        expectedOrigin?.span,
                        null,
                        expectedOrigin,
                    )
                )
            }
        }

        return InferResult(
            resultType,
            combined,
            enumCallOrigin(enumType, payloadTypes, call, argOrigins),
            adoptedTypeArgs != null,
        )
    }

    /**
     * 载荷第 [payloadIndex] 位的期望类型：仅当该载荷类型**正好等于**某个枚举类型实参
     * （`Some(T)` 这种直接出现）时才下推；更深嵌套（`Cons(T, List<T>)` 里的 T）不下推，
     * 由原有的粗载荷约束兜底。
     */
    private fun directPayloadExpectation(
        payloadTypes: Seq<Type>,
        payloadIndex: Int,
        enumTypeArgs: Seq<Type>,
        expectedTypeArgs: Seq<Type>,
        expectedOrigin: TypeOrigin?,
    ): ExpectedType? {
        if (payloadIndex >= payloadTypes.size) return null
        val payloadType = payloadTypes.get(payloadIndex)
        for ((j, typeArg) in enumTypeArgs.withIndex()) {
            if (typeArg == payloadType && j < expectedTypeArgs.size) {
                return ExpectedType(expectedTypeArgs.get(j), expectedOrigin?.childAt(j))
            }
        }
        return null
    }

    /**
     * 变体构造结果的来源树：把「枚举类型实参」映射回写出它的那个载荷实参。
     *
     * 只映射**直接出现**的类型参数（`Some(T)`：载荷类型正好等于某个枚举类型实参）——
     * 这样 `Option<Int>` × `Option.Some("s")` 下钻到最内层时，使用方 label 能落到 `"s"`。
     * 更深的嵌套（`Cons(T, List<T>)` 里的 T）与单元变体（类型实参没有对应源码）用
     * [TypeOrigin.Unknown] 占位，保证下标对齐，求解器遇到它时保持上一层定位。
     */
    private fun enumCallOrigin(
        enumType: Type,
        payloadTypes: Seq<Type>,
        call: Expr.Call,
        argOrigins: Seq<TypeOrigin>,
    ): TypeOrigin {
        val typeArgs = (enumType as? Type.App)?.args ?: return TypeOrigin(call.span)
        val children = Seq<TypeOrigin>(typeArgs.size)
        for (typeArg in typeArgs) {
            var child = TypeOrigin.Unknown
            for ((i, payloadType) in payloadTypes.withIndex()) {
                if (payloadType == typeArg && i < argOrigins.size) {
                    child = argOrigins.get(i)
                    break
                }
            }
            children.add(child)
        }
        return TypeOrigin(call.span, children)
    }

    /**
     * 收集 [fnType]（泛型函数签名）中出现的、不在 [declared] 中的自由类型变量
     * （未注解形参/返回值引入的变量）。暂挂 scheme 时把这些变量一并量化，
     * 实例化时统一替换为 fresh 变量，避免跨调用点共享。
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

    /**
     * 推断一个表达式。
     *
     * @param expected 自上而下的期望类型（双向检查，见 [ExpectedType]）；为 null 表示纯自下而上推断。
     *   能结构化消费期望的分支（字面量/标识符/数组字面量/变体构造器）会就地完成比较并置
     *   [InferResult.expectedHandled]，调用方据此跳过重复的兜底约束。
     */
    private fun inferExpr(expr: Expr?, expected: ExpectedType? = null): InferResult {
        if (expr == null) return InferResult(BuiltinType.Unknown, Seq<Constraint>(0))

        return when (expr) {
            is Expr.Literal -> {
                val result = InferResult(BuiltinType.toType(expr.token.type), Seq(0), TypeOrigin(expr.span))
                if (expected == null) result else withExpectedCheck(result, expected, expr.span)
            }

            is Expr.Identifier -> {
                val result = inferIdentifier(expr)
                if (expected == null) result else withExpectedCheck(result, expected, expr.span)
            }

            is Expr.Tuple -> {
                // 元组：逐元素推断，产出 Type.TupleType；子项来源按下标对齐（元组元素失配时能指向具体元素）
                val combined = Seq<Constraint>(0)
                val elementTypes = Seq<Type>(0)
                val elementOrigins = Seq<TypeOrigin>(expr.elements.size)
                for (e in expr.elements) {
                    val r = inferExpr(e)
                    combined.addAll(r.constraints)
                    elementTypes.add(r.type)
                    elementOrigins.add(r.origin ?: TypeOrigin.Unknown)
                }
                InferResult(Type.TupleType(elementTypes), combined, TypeOrigin(expr.span, elementOrigins))
            }

            is Expr.Annotation -> {
                val r = inferExpr(expr.expr, expected)
                InferResult(r.type, r.constraints, r.origin, r.expectedHandled)
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
                // 数组字面量：所有元素统一为一个元素类型，产出 Type.Arr(elementType)。
                // 期望是数组（`Array<T>` / `Arr(T)`）时元素类型**直接取期望的元素类型**，
                // 于是元素错误就地报在出错的那个元素上（而不是整个数组字面量）。
                val expectedElement = expected?.let { expectedElementOf(it) }
                val elementType = expectedElement?.type ?: solver.freshVar()
                val combined = Seq<Constraint>(0)
                for (e in expr.elements) {
                    val r = inferExpr(e, expectedElement)
                    combined.addAll(r.constraints)
                    // 元素内部已消费期望时不再重复加元素约束
                    if (!r.expectedHandled) {
                        combined.add(
                            Constraint.Equal(
                                r.type,
                                elementType,
                                e.span,
                                expectedElement?.span,
                                r.origin,
                                expectedElement?.origin,
                            )
                        )
                    }
                }
                InferResult(Type.Arr(elementType), combined, null, expectedElement != null)
            }

            is Expr.Index -> {
                // 索引：list 必须是 Array<result>，index 必须是 Int
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
                // `枚举名.变体(...)`：变体构造器调用，直接按载荷检查实参
                val variantSymbol = enumVariantSymbolOf(expr.callee)
                if (variantSymbol != null) return inferEnumVariantCall(expr, variantSymbol, expected)

                val callee = inferExpr(expr.callee)
                val combined = Seq<Constraint>(0)
                combined.addAll(callee.constraints)
                // 类型实参挂在 callee 的 Identifier 上（`foo<Int>(...)`），由 inferExpr(Identifier)

                // 若 callee 是已知具名函数：取形参声明位置与声明信息（后者用于期望类型下推）
                val calleeInfo = TypeAnnotationSpansOf(expr.callee)
                val calleeDecls = TypeAnnotationsOf(expr.callee)
                val calleeDefId = (expr.callee as? Expr.Identifier)?.defId

                // 实参：逐个推断；注解类型已知时把「期望类型」下推，错误就地报在实参子表达式上
                val argResults = Seq<InferResult>(expr.args.size)
                for ((i, a) in expr.args.withIndex()) {
                    val declared = if (calleeDecls != null && i < calleeDecls.size) calleeDecls.get(i) else null
                    val ar = inferExpr(a, pushableExpected(declared, calleeDefId))
                    combined.addAll(ar.constraints)
                    argResults.add(ar)
                }

                // 1) 结构链接：callee 必须是「实参数量对应的函数类型」。
                //    每个形参用 fresh 变量占位，等待与实参逐一约束。
                //    t1=调用处合成的函数类型（使用方/实际），t2=函数声明类型（声明方/期望）。
                val paramVars = Seq<Type>(argResults.size)
                repeat(argResults.size) { paramVars.add(solver.freshVar()) }
                val resVar = solver.freshVar()
                val fnType = Type.Func(paramVars, resVar)
                combined.add(Constraint.Equal(fnType, callee.type, expr.callee.span, calleeInfo?.first))

                // 2) 逐实参约束：使用方=实参自身 span（label），声明方=形参注解位置（label）。
                //    这样每个不匹配的实参单独报错，而不是整个 Call 一个错误。
                //    实参已消费期望类型时跳过（那次比较在实参内部已完成，避免重复报错）。
                val TypeAnnotationSpans = calleeInfo?.second
                val declSpanCount = TypeAnnotationSpans?.size ?: 0
                val declCount = calleeDecls?.size ?: 0
                for ((i, ar) in argResults.withIndex()) {
                    if (ar.expectedHandled) continue
                    // 实参数量可能超过形参声明数量（结构链接约束会另行报「参数数量不匹配」），
                    // 越界的实参没有对应形参声明位置 → declSpan 取 null（退化为仅使用方 label）。
                    val declSpan = if (i < declSpanCount) TypeAnnotationSpans?.get(i) else null
                    val declOrigin = if (i < declCount) calleeDecls?.get(i)?.origin else null
                    combined.add(
                        Constraint.Equal(
                            ar.type,
                            paramVars[i],
                            expr.args[i].span,
                            declSpan,
                            ar.origin,
                            declOrigin,
                        )
                    )
                }

                InferResult(resVar, combined)
            }

            is Expr.Get -> {
                // `枚举名.变体`：变体访问；单元变体得到枚举类型，带载荷变体得到构造器函数
                val variantSymbol = enumVariantSymbolOf(expr)
                if (variantSymbol != null) {
                    val explicitArgs = (expr.obj as? Expr.Identifier)?.typeArgs
                    return InferResult(instantiateVariant(variantSymbol, expr, explicitArgs), Seq(0))
                }
                // `枚举名.xxx` 中 xxx 不是变体：Resolver 已报「没有这个变体」，这里静默降级
                if (enumTypeSymbolOf(expr.obj) != null) return InferResult(BuiltinType.Error, Seq(0))

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
     * - 标识符：经 [DefId] 查 [SymbolTable] 得符号类型；支持嵌套泛型注解（`Array<Int>`、`Array<T>`），
     *   转换细节见 [typeArgToType]；
     * - 元组：递归转换元素，产出 [Type.TupleType]；
     * - 无法转换（defId 缺失 / 符号不存在 / 其它表达式）：返回 [Type.Error]。
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
     * 类型注解 → 来源树（与 [annotationToType] 同构，纯诊断用途，见 [TypeOrigin]）。
     *
     * 多个枚举值（`A | B`）已由 [annotationToType] 报「暂不支持」，这里退化为整体注解、不参与下钻。
     */
    private fun annotationToOrigin(annotation: Expr.Annotation): TypeOrigin {
        val variants = annotation.annotations
        if (variants.size != 1) return TypeOrigin(annotation.span)
        return variantToOrigin(variants[0])
    }

    /**
     * 单个枚举值表达式 → 来源树（与 [variantToType] 同构）：
     * 根 span 是**类型表达式本身**（`x: Option<Int>` 里是 `Option<Int>`，不含形参名与冒号），
     * 子项依类型实参展开。
     */
    private fun variantToOrigin(expr: Expr): TypeOrigin = when (expr) {
        is Expr.Identifier -> identifierOrigin(expr)
        is Expr.Tuple -> TypeOrigin(expr.span, expr.elements.map { variantToOrigin(it) })
        else -> TypeOrigin(expr.span)
    }

    /**
     * 类型标识符 → 来源树：根 span = 该类型表达式（`Array<Int>`），
     * 子项 = 各类型实参的来源（`Array<Int>` 的 `Int`），与 `Type.App` 的子项同序。
     *
     * 裸 `Array` 会被转成 `App(Array, [?])`（多一个子项而无源码可指），
     * 下钻时子项越界 → 求解器退化为根 span，即 `Array` 本身。
     */
    private fun identifierOrigin(expr: Expr.Identifier): TypeOrigin {
        val args = expr.typeArgs
        if (args == null || args.isEmpty) return TypeOrigin(expr.span)
        return TypeOrigin(expr.span, args.map { identifierOrigin(it) })
    }

    /**
     * 类型实参表达式 → [Type]（支持嵌套泛型：`Int`、`Array<Int>`、`Array<Array<T>>`）。
     *
     * 裸标识符：
     * - 类型参数（`T`）→ 其类型变量（由 [analyzeFnStmt] 写入符号）；
     * - 内置/已知类型（`Int`）→ 符号类型；
     * - 裸 `Array` → 宽松视为 `Array<?>`（元素类型由后续约束推断，与旧行为一致）；
     * - 解析失败（defId 缺失）→ [Type.Error]（Resolver 已报"未声明的类型名"）。
     *
     * 嵌套应用 `Head<Args...>`：
     * - 头部为 `Array` → [Type.App]（实参数量必须是 1）；
     * - 头部为类型参数 → 高阶类型（kind `* -> *`），报错（TODO）；
     * - 其它 → 报"不接受类型实参"。
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
            if (symbol?.values?.get(Symbol.ENUM_KEY) == true) {
                return enumAppType(symbol, argTypes, expr)
            }
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
        if (symbol?.values?.get(Symbol.ENUM_KEY) == true) {
            // 裸写 `Option`：类型实参全部待推断（与裸 `Array` 的宽松处理一致）
            return enumAppType(symbol, Seq<Type>(0), expr)
        }
        return when {
            symbol == null -> Type.Error
            symbol.values.get(Symbol.TYPE_PARAM_KEY) == true -> symbol.type
            symbol.type == BuiltinType.Array ->
                // 裸 `Array`：宽松视为 `Array<Unknown>`
                Type.App(BuiltinType.Array, Seq.with(solver.freshVar()))

            else -> symbol.type
        }
    }

    /**
     * 枚举类型应用 `Option<Int>`。
     *
     * - 非泛型枚举 `Color`：`Type.Con("Color")`；写类型实参（`Color<Int>`）报「不接受类型实参」；
     * - 泛型枚举 `Option<T>`：实参数量必须等于声明的类型参数数量（否则 [diag.type-arg-count]）；
     *   [argTypes] 为空（裸写 `Option`）时按「全部待推断」补 fresh 变量。
     */
    private fun enumAppType(symbol: Symbol, argTypes: Seq<Type>, at: Expr): Type {
        val declaredCount = symbol.values.get(Symbol.ENUM_TYPE_PARAM_COUNT_KEY) as? Int ?: 0
        if (declaredCount == 0) {
            if (!argTypes.isEmpty) {
                error(bundle.format("diag.type-not-generic", symbol.name)).label(at, "")
                return Type.Error
            }
            return Type.Con(symbol.name)
        }
        if (argTypes.isEmpty) {
            val args = Seq<Type>(declaredCount)
            repeat(declaredCount) { args.add(solver.freshVar()) }
            return Type.App(Type.Con(symbol.name), args)
        }
        if (argTypes.size != declaredCount) {
            error(bundle.format("diag.type-arg-count", symbol.name, declaredCount, argTypes.size))
                .label(at, "")
            return Type.Error
        }
        return Type.App(Type.Con(symbol.name), argTypes)
    }

    /**
     * 按调用点实例化泛型函数的类型方案。
     *
     * - 无显式实参：全部 fresh 变量（全推断）；
     * - 有显式实参：数量必须等于声明的类型参数数量（[diag.explicit-type-arg-count]），
     *   按序替换；数量不一致时报错并回退为全推断，继续编译。
     *
     * @param declaredCount 声明的类型参数数量（区别于 [TypeScheme.typeVars] 的运行时大小——
     *   scheme 求解后可能并入额外的泛化变量，显式实参只对声明的参数计数）
     */
    private fun instantiateScheme(symbol: Symbol, expr: Expr.Identifier, declaredCount: Int): Type {
        val explicitArgs = expr.typeArgs
        val argCount = explicitArgs?.size ?: 0
        if (argCount != 0 && argCount != declaredCount) {
            val diagnostic = error(bundle.format("diag.explicit-type-arg-count", declaredCount, argCount))
                .label(expr, bundle.format("diag.explicit-type-arg-count.help", declaredCount))
            noteTypeParamSource(diagnostic, symbol.id, declaredCount)
            labelExtraTypeArgs(diagnostic, explicitArgs, declaredCount)
            // 回退以继续编译：非泛型符号 → 返回原符号类型（占位 scheme 不能用作实际类型）；
            // 泛型函数 → 全推断（fresh 变量）。
            return if (declaredCount == 0) {
                symbol.type
            } else {
                symbol.typeScheme.instantiateWith(Seq<Type>(0)) { solver.freshVar() }
            }
        }
        if (argCount == 0) {
            return symbol.typeScheme.instantiateWith(Seq<Type>(0)) { solver.freshVar() }
        }
        val argTypes = Seq<Type>(argCount)
        for (a in explicitArgs!!) argTypes.add(typeArgToType(a))
        return symbol.typeScheme.instantiateWith(argTypes) { solver.freshVar() }
    }

    /**
     * 当前泛型参数嵌套栈中所有类型参数索引的并集（泛化时排除外层函数已量化的变量）。
     * 返回副本，随栈变化不受影响。
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
     * 泛化出的新变量追加在后；按索引去重。
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

    // 从 `Identifier` 或 `Annotation(Identifier, ...)` 中取出标识符
    private fun unwrapIdentifier(expr: Expr): Expr.Identifier? {
        return when (expr) {
            is Expr.Identifier -> expr
            is Expr.Annotation -> expr.expr as? Expr.Identifier
            else -> null
        }
    }

    /**
     * 若 [calleeExpr] 是已知具名函数，返回其形参声明信息（与形参**下标**一一对应，见 [TypeAnnotations]）；
     * 不是具名函数、或无任何声明信息时返回 `null`（调用处退化为只用 span 定位、不下推期望类型）。
     */
    private fun TypeAnnotationsOf(calleeExpr: Expr): Seq<TypeAnnotation>? {
        val ident = calleeExpr as? Expr.Identifier ?: return null
        val defId = ident.defId ?: return null
        return TypeAnnotations.get(defId)
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
    private fun TypeAnnotationSpansOf(calleeExpr: Expr): Pair<Span, Seq<Span?>>? {
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
        context.diagHandler.addError(e)
        return e
    }

    // 警告
    private fun warning(name: String): SemanticDiag {
        val w = SemanticDiag(name, Diagnostic.DiagLevel.WARNING)
        context.diagHandler.addWarning(w)
        return w
    }

    // 当前函数返回上下文：期望返回类型 + 返回类型注解位置（不匹配报错的声明方 label 用）
    private data class ReturnContext(
        val expected: Type,
        val declSpan: Span,
        /** 返回值注解的类型来源：把声明方 label 收窄到出错子项（如 `Array<Int>` 的 `Int`） */
        val expectedOrigin: TypeOrigin?,
        /** 可下推给 `return` 表达式的期望类型（注解含本函数类型参数时为 null） */
        val push: ExpectedType?,
    )

    /**
     * 类型注解的声明信息：注解类型 + 类型来源。
     *
     * 形参、`set` 变量、返回值都用它承载「声明方类型」：既用于双向检查下推 [ExpectedType]，
     * 也用于类型不匹配时的声明方 label。
     *
     * @param type 注解类型；无注解时为 [BuiltinType.Unknown]（[isPresent] 为假）
     * @param origin 类型表达式（`Option<Int>`）的来源树
     */
    private class TypeAnnotation(val type: Type, val origin: TypeOrigin?) {
        val isPresent: Boolean get() = type != BuiltinType.Unknown

        companion object {
            /** 无注解 */
            val None = TypeAnnotation(BuiltinType.Unknown, null)
        }
    }

    /**
     * 泛型函数登记信息：求解完成后基于已求解的 body 重建 TypeScheme。
     *
     * @param declaredVars 声明处按序分配的类型参数变量（显式实参按此计数/替换）
     * @param envFreeVars 泛化时需排除的外层类型参数索引（声明处的嵌套栈快照）
     */
    private class GenericFnInfo(
        val symbol: Symbol,
        val declaredVars: Seq<Type.Var>,
        val envFreeVars: IntSet,
    )

    // endregion
}