package mlogix.compiler.passes.resolution

import arc.struct.Seq
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Pattern
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.symbol.*
import mlogix.compiler.core.type.BuiltinType
import mlogix.compiler.core.type.Type
import mlogix.compiler.core.type.TypeOrigin
import mlogix.compiler.core.type.TypeScheme
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.diagnostic.Diagnostic.SemanticDiag
import mlogix.compiler.ir.ResolutionResult
import mlogix.util.I18N.bundle

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
class Resolver(private val context: CompilerContext) {
    private lateinit var sourceFile: SourceFile
    private lateinit var symbolTable: SymbolTable
    private lateinit var rootScope: Scope

    /** 当前正在解析的方法所属结构体（[DefId]）：方法体内裸字段名改写为隐式 `self.字段名` 时用 */
    private var currentStructDefId: DefId? = null

    /**
     * 一个文件 一次调用 就地改写AST
     */
    fun resolve(ast: Stmt, sourceFile: SourceFile): ResolutionResult {
        this.sourceFile = sourceFile
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
            val symbol = symbolTable.declare(type.name, type, Span(sourceFile.index, 0, 0))
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

            is Stmt.Use -> {
                // use / import 暂不处理
            }

            is Stmt.Block -> {
                val child = scope.child()
                for (s in stmt.stmts) resolveStmt(s, child)
            }

            is Stmt.ExprStmt -> {
                resolveExpr(stmt.expr, scope)
            }

            is Stmt.If -> {
                resolveExpr(stmt.condition, scope)
                resolveStmt(stmt.thenBranch, scope)
                resolveStmt(stmt.elseBranch, scope)
            }

            is Stmt.Match -> {
                resolveExpr(stmt.scrutinee, scope)
                stmt.branches?.let { branches ->
                    for (branch in branches) {
                        // 每个分支独立作用域：模式里的绑定只在该分支（模式 + 分支体）内可见
                        val armScope = scope.child()
                        resolvePattern(branch.pattern, armScope)
                        resolveStmt(branch.body, armScope)
                    }
                }
            }

            is Stmt.For -> {
                // for 循环引入新作用域；flag 是循环标签，不是变量，不解析
                val child = scope.child()
                stmt.varDecl?.let { resolveLoopVar(it, child) }
                stmt.expr?.let { resolveExpr(it, child) }
                resolveStmt(stmt.body, child)
            }

            is Stmt.While -> {
                // flag 是循环标签，不解析
                resolveExpr(stmt.expr, scope)
                resolveStmt(stmt.body, scope)
            }

            is Stmt.Break, is Stmt.Continue -> {
                // flag 是循环标签，不解析
            }

            is Stmt.Fn -> {
                resolveFnStmt(stmt, scope)
            }

            is Stmt.Return -> {
                stmt.expr?.let { resolveExpr(it, scope) }
            }

            is Stmt.Assign -> {
                resolveExpr(stmt.`var`, scope)
                resolveExpr(stmt.value, scope)
            }

            is Stmt.SetVar -> {
                resolveSetVarStmt(stmt, scope)
            }

            is Stmt.Struct -> {
                resolveStructStmt(stmt, scope)
            }

            is Stmt.Enum -> {
                resolveEnumStmt(stmt, scope)
            }
        }
    }

    /**
     * 函数声明：登记函数符号、挂载 TypeScheme、绑定形参、解析函数体。
     */
    private fun resolveFnStmt(stmt: Stmt.Fn, scope: Scope) {
        val fnName = (stmt.name?.literal as? String) ?: stmt.name?.type?.toString()
        if (fnName == null) {
            // 匿名/无名函数：只解析函数体，不登记
            resolveStmt(stmt.body, scope)
            return
        }

        val fnSymbol = declare(fnName, BuiltinType.Fn, stmt.name?.span ?: stmt.span, scope)
        stmt.defId = fnSymbol?.id

        // 类型方案（含泛型参数）由 TypeInferencer 构建——它持有求解器，能分配类型变量；
        // 这里只挂一个占位，避免 null（见 analyzeFnStmt）
        fnSymbol?.typeScheme = TypeScheme(Seq(), BuiltinType.Fn)

        resolveFnSuffix(stmt, scope)
    }

    /**
     * 符号登记之后的公共部分：形参、返回值注解与函数体的名称解析。
     *
     * 结构体方法复用这一段（见 [resolveMethodStmt]）：方法符号在**成员表**里登记过一次，
     * 不能再走 [resolveFnStmt] 的 `declare`（那会造出第二个同名符号，成员表与 `defId`
     * 各自指向一个，方法体的类型写进其中一个、调用点却查到另一个）。
     */
    private fun resolveFnSuffix(stmt: Stmt.Fn, scope: Scope) {
        // 形参与函数体在子作用域中；泛型形参（`fn foo<T, E>`）先绑定，类型注解才能引用
        val fnScope = scope.child()
        stmt.typeParams?.let { typeParams ->
            for (typeParam in typeParams) resolveTypeParam(typeParam, fnScope)
        }
        stmt.params?.let { params ->
            for (p in params) {
                // 接收者 `self`：不是名字绑定（它不进作用域），但形如 `self: Point` 的注解里
                // 类型名仍要解析，供「类型名调用方法」（`Point.dist(p)`）解析到结构体符号
                val selfRef = selfRefOf(p)
                if (selfRef != null) {
                    if (p is Expr.Annotation) {
                        for (annotation in p.annotations) resolveAnnotationNames(annotation, fnScope)
                    }
                    continue
                }
                bindParam(p, fnScope)
            }
        }

        // 返回值声明中的类型：同样只做类型名的名称解析。
        // - 具名结果（`-> r : Int`）：解析冒号**之后**的类型表达式；`r` 是结果名，不是类型名，不能查表；
        // - 裸写结果（`-> Int`、`-> T`、`-> Array<Int>`，以及 `?` 在解析期合成的 `Null`）：
        //   整个表达式就是类型表达式，必须一起做名称解析——此前只处理具名形式，裸写结果永远拿不到
        //   `defId`，类型推断阶段只能退化成 `Type.Dummy`，返回值注解事实上被忽略。
        stmt.results?.let { results ->
            for (result in results) {
                if (result is Expr.Annotation) {
                    for (variant in result.annotations) resolveAnnotationNames(variant, fnScope)
                } else {
                    resolveAnnotationNames(result, fnScope)
                }
            }
        }

        resolveStmt(stmt.body, fnScope)
    }

    /**
     * 绑定一个泛型形参（`fn foo<T, E>`）：登记符号并标记为类型参数。
     * 使形参返回函数体里的类型注解（`x: T`、`-> Array<T>`）能解析到它。
     *
     * 声明处嵌套类型参数（`fn foo<T, E<U>>`）意味着 E 类型构造器"（高阶类型，
     * kind `* -> *`），当前类型系统尚不支持——报诊断并只绑定头部名字 E（见 TODO）
     */
    private fun resolveTypeParam(typeParam: Expr.Identifier, scope: Scope) {
        val name = (typeParam.token.literal as? String) ?: typeParam.token.type.toString()
        // TODO: 支持高阶类型（类型构造器作为类型参数）后移除这段诊断。
        //  届时 `E<U>` 中的 U 应作为 E 的形参绑定到独立作用。
        if (typeParam.typeArgs != null && !typeParam.typeArgs.isEmpty) {
            error(bundle.format("diag.hkt-not-supported", name))
                .label(typeParam, bundle.get("diag.hkt-not-supported.help"))
        }
        val symbol = declare(name, BuiltinType.Unknown, typeParam.span, scope)
        symbol?.values?.put(Symbol.TYPE_PARAM_KEY, true)
        typeParam.defId = symbol?.id
    }

    /**
     * 在另一个作用域里绑定**同名类型参数**（不回填 [Expr.Identifier.defId]）。
     *
     * 用于「字段默认值」这种只见类型参数、不见字段的作用域：[resolveTypeParam] 会把
     * `defId` 覆盖成后一个符号，而字段类型注解必须稳定指向同一个符号。
     */
    private fun bindTypeParamName(typeParam: Expr.Identifier, scope: Scope) {
        val name = (typeParam.token.literal as? String) ?: typeParam.token.type.toString()
        val symbol = declare(name, BuiltinType.Unknown, typeParam.span, scope)
        symbol?.values?.put(Symbol.TYPE_PARAM_KEY, true)
    }

    /**
     * 形参绑定：形参可能是 `Identifier` `Annotation(Identifier, 注解...)`。
     * 绑定名称 DefId，并把形参标识符 defId 填好。
     */
    private fun bindParam(param: Expr, scope: Scope) {
        val ident = unwrapIdentifier(param) ?: return
        val name = (ident.token.literal as? String) ?: ident.token.type.toString()
        val symbol = declare(name, BuiltinType.Unknown, ident.span, scope)
        ident.defId = symbol?.id

        // 形参类型注解：这里只做「类型名 DefId」的名称解析。
        // 注解 Type 的转换与约束生成 TypeInferencer 完成（职责分离，resolveAnnotationNames）。
        if (param is Expr.Annotation) {
            for (variant in param.annotations) resolveAnnotationNames(variant, scope)
        }
    }

    /**
     * 循环变量绑定（for 循环 varDecl）。
     */
    private fun resolveLoopVar(varDecl: Expr.Identifier, scope: Scope) {
        val name = (varDecl.token.literal as? String) ?: varDecl.token.type.toString()
        val symbol = declare(name, BuiltinType.Unknown, varDecl.span, scope)
        varDecl.defId = symbol?.id
    }

    /**
     * `set` 声明变量：登记符号并绑定；随后解析其赋值语句。
     * var 可能是 `Identifier` `Annotation(Identifier, ...)`。
     */
    private fun resolveSetVarStmt(stmt: Stmt.SetVar, scope: Scope) {
        val ident = unwrapIdentifier(stmt.`var`)
        if (ident != null) {
            // `set var<T>`：变量名携带类型实参是误用——变量不是泛型，
            // 类型应写在注解里（`set var : Foo<Int>`）
            // TODO: 若未来支持 Rust turbofish 风格的泛型函数引用赋值（`set f = id<Int>`），
            //  只允许 RHS 携带类型实参，LHS 依旧不允许
            if (ident.typeArgs != null && !ident.typeArgs.isEmpty) {
                error(bundle.get("diag.var-with-type-args"))
                    .label(ident, bundle.get("diag.var-with-type-args.help"))
            }
            val name = (ident.token.literal as? String) ?: ident.token.type.toString()
            val symbol = declare(name, BuiltinType.Unknown, ident.span, scope)
            ident.defId = symbol?.id
        }
        // 变量类型注解中的类型名也要解析（`set a : Array<Int>` 里的 `Array` `Int`）
        if (stmt.`var` is Expr.Annotation) {
            for (variant in stmt.`var`.annotations) resolveAnnotationNames(variant, scope)
        }
        resolveStmt(stmt.assign, scope)
    }

    /**
     * 枚举声明：登记枚举类型符号（[Symbol.ENUM_KEY]）与 `变体名 → 变体 DefId` 表。
     *
     * 变体符号**不绑定到作用域**——变体只能经 `枚举名.变体` 访问（Rust 用 `::`，本语言用 `.`），
     * 因此不同枚举可以有同名变体（`Option.None` 与 `Result.None` 互不干扰），
     * 变体名也不会污染外层作用域。
     *
     * 变体载荷只做「类型名 → DefId」的名称解析（填在注解/类型实参的 `defId` 上），
     * 载荷类型由 TypeInferencer 转换并转为构造器类型。
     */
    private fun resolveEnumStmt(stmt: Stmt.Enum, scope: Scope) {
        val name = (stmt.name.token.literal as? String) ?: stmt.name.token.type.toString()
        val enumSymbol = declare(name, BuiltinType.Unknown, stmt.name.span, scope)
        stmt.defId = enumSymbol?.id
        if (enumSymbol == null) return

        enumSymbol.values.put(Symbol.ENUM_KEY, true)
        enumSymbol.values.put(Symbol.ENUM_TYPE_PARAM_COUNT_KEY, stmt.typeParams?.size ?: 0)
        val variants = EnumVariants()
        enumSymbol.values.put(Symbol.ENUM_VARIANTS_KEY, variants)

        // 泛型形参先绑定：变体载荷（`Some(T)`、`Named { x: T }`）要能解析到它
        val enumScope = scope.child()
        stmt.typeParams?.let { typeParams ->
            for (typeParam in typeParams) resolveTypeParam(typeParam, enumScope)
        }

        for (variant in stmt.variants) {
            val variantName = (variant.name.token.literal as? String) ?: variant.name.token.type.toString()
            if (variants.contains(variantName)) {
                error(bundle.format("diag.duplicate-variant", name, variantName))
                    .label(variant.name, "")
                continue
            }
            val variantSymbol = symbolTable.declare(variantName, BuiltinType.Unknown, variant.name.span)
            variantSymbol.values.put(Symbol.ENUM_VARIANT_KEY, true)
            variants.put(variantName, variantSymbol.id)
            variant.name.defId = variantSymbol.id

            // 每个变体独立子作用域：结构体变体的字段名在此登记（重复字段名即重复定义）
            val variantScope = enumScope.child()
            for (field in stmt.fieldsOf(variant)) {
                if (field is Expr.Annotation) {
                    // 结构体变体 `Named { name: Str }`：字段名是新定义，冒号后才是类型名
                    val fieldIdent = field.expr as? Expr.Identifier
                    if (fieldIdent != null) {
                        val fieldName =
                            (fieldIdent.token.literal as? String) ?: fieldIdent.token.type.toString()
                        declare(fieldName, BuiltinType.Unknown, fieldIdent.span, variantScope)
                            ?.let { fieldIdent.defId = it.id }
                    }
                    for (annotation in field.annotations) resolveAnnotationNames(annotation, variantScope)
                } else if (variant !is Stmt.Enum.EnumVariant.Struct) {
                    // 元组变体 `Rgb(Num, Num)`：载荷本身就是类型表达式
                    // （结构体变体缺少 `: 类型` 时 Parser 已报错，不再当类型名解析以免级联报错）
                    resolveAnnotationNames(field, variantScope)
                }
            }
        }
    }

    // ========== 模式解析 ==========
    /**
     * match 分支模式解析：
     * - `_`：不绑定任何名字；
     * - 裸标识符：在**当前分支作用域**声明新符号（绑定，Rust 风格；同名遮蔽外层名字是允许的）；
     * - `枚举名.变体(...)`：复用表达式的变体访问解析（同一张变体表、同一个「没有这个变体」诊断），
     *   载荷模式递归解析（嵌套绑定同样落在当前分支作用域）。
     */
    private fun resolvePattern(pattern: Pattern, scope: Scope) {
        when (pattern) {
            is Pattern.Wildcard -> Unit

            is Pattern.Binding -> {
                val name = (pattern.name.token.literal as? String) ?: pattern.name.token.type.toString()
                val symbol = declare(name, BuiltinType.Unknown, pattern.name.span, scope)
                pattern.name.defId = symbol?.id
            }

            is Pattern.Variant -> {
                val typeSymbol = typeSymbolOf(pattern.path.obj, scope)
                // 只有枚举才能做变体模式。两种「不是枚举」都要报，但用词不同：
                // - 左侧是**别的类型名**（结构体名等）：直接说「它不是枚举类型」；
                // - 左侧根本不是类型名（是变量、甚至未声明）：先按普通表达式解析
                //   （未声明时在此报「未声明的标识符」），再按「它不是枚举类型」报。
                val enumSymbol = typeSymbol?.takeIf { it.values.get(Symbol.ENUM_KEY) == true }
                if (enumSymbol != null) {
                    resolveEnumVariantAccess(pattern.path, enumSymbol, scope)
                } else {
                    resolveExpr(pattern.path.obj, scope)
                    val objSymbol = (pattern.path.obj as? Expr.Identifier)?.defId?.let { symbolTable.get(it) }
                    val notEnum = typeSymbol ?: objSymbol?.takeIf { it.values.get(Symbol.ENUM_KEY) != true }
                    if (notEnum != null) {
                        error(bundle.format("diag.pattern-not-enum", notEnum.name))
                            .label(pattern.path.obj, bundle.get("diag.pattern-not-enum.help"))
                    }
                }
                for (arg in pattern.args) resolvePattern(arg, scope)
            }
        }
    }

    // ========== 表达式解析==========
    /**
     * @param isCallee 本表达式正处在**被调用位置**（`f(...)` 的 `f`）：方法体里的裸方法名
     *   只在调用位置才改写为隐式 `self.方法名`（方法不能当左值）
     */
    private fun resolveExpr(expr: Expr?, scope: Scope, isCallee: Boolean = false) {
        if (expr == null) return
        // 方法体里的裸字段名 / 裸方法名先改写成隐式接收者访问，再按一般的成员访问解析
        // （改写在**进入 when 之前**完成，改写结果沿用同一条路径，不必在两处维护）
        if (expr is Expr.Identifier) {
            val rewritten = implicitSelfField(expr, scope)
            if (rewritten !== expr) {
                resolveExpr(rewritten, scope)
                return
            }
            val rewrittenMethod = implicitSelfMethod(expr, scope, isCallee)
            if (rewrittenMethod !== expr) {
                resolveExpr(rewrittenMethod, scope)
                return
            }
        }
        when (expr) {
            is Expr.Identifier -> {
                val name = (expr.token.literal as? String) ?: expr.token.type.toString()
                val defId = scope.lookup(name)
                if (defId == null) {
                    error(bundle.format("diag.undeclared-identifier", name))
                        .label(expr.token, bundle.get("diag.undeclared-identifier.help"))
                } else {
                    expr.defId = defId
                }
                // 值位置携带的类型实参（`foo<Int>`、`id<Array<Str>>`）是类型名，按类型名解析
                expr.typeArgs?.let { args -> for (a in args) resolveAnnotationNames(a, scope) }
            }

            is Expr.SelfRef -> {
                // 隐式接收者：在方法体里由 [implicitSelfField] 构造，方法签名里由 [resolveMethodStmt] 补齐
                expr.instanceDefId = currentStructDefId
            }

            is Expr.Literal, is Expr.Dummy -> Unit

            is Expr.Tuple -> {
                for (e in expr.elements) resolveExpr(e, scope)
            }

            is Expr.Annotation -> {
                // 只解析被注解的表达式主体；注解中的类型名由类型系统后续处理，
                // 这里不解析，避免把类型名误报成未声明的标识。
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
                // 被调用位置的裸标识符可能是同结构体的方法名：**就地**改写成 `self.方法名`
                // （不是只改写被调用者再新建 Call——那会变成「取方法引用再调用」，语义不同）
                val calleeIdent = expr.callee as? Expr.Identifier
                if (calleeIdent != null) {
                    val rewrittenCallee = implicitSelfMethod(calleeIdent, scope, isCallee = true)
                    if (rewrittenCallee !== calleeIdent) {
                        expr.callee = rewrittenCallee
                        resolveExpr(rewrittenCallee, scope)
                        for (a in expr.args) resolveExpr(a, scope)
                        return
                    }
                }
                resolveExpr(expr.callee, scope)
                for (a in expr.args) resolveExpr(a, scope)
            }

            is Expr.Get -> {
                val typeSymbol = typeSymbolOf(expr.obj, scope)
                if (typeSymbol != null) {
                    // 左侧是**类型名**：右侧不是普通标识符，而是该类型的成员（枚举变体 / 结构体成员）
                    if (typeSymbol.values.get(Symbol.ENUM_KEY) == true) {
                        resolveEnumVariantAccess(expr, typeSymbol, scope)
                    } else {
                        resolveStructMemberAccess(expr, typeSymbol, scope)
                    }
                } else if (expr.obj is Expr.SelfRef) {
                    // 隐式接收者访问（方法体里裸写字段名/方法名改写而来）：成员只在**当前结构体**
                    // 的成员表里查。左侧是 `self`，不是值上的成员访问，因此不能用
                    // [resolveOperand]（那会把成员名当普通变量名查表，方法名不在作用域里 → 误报未声明）
                    resolveSelfMemberAccess(expr, scope)
                } else {
                    // 值上的成员访问（`p.x`、`p.dist()`）：字段名由 TypeInferencer 按接收者类型解析，
                    // 这里**只解析接收者**，不把字段名当变量名查表（否则 `p.x` 的 `x` 会误报「未声明」）
                    resolveOperand(expr.obj, scope)
                }
            }
        }
    }

    /**
     * 隐式接收者上的成员访问 `self.成员`：只在当前结构体的字段表/方法表里查，
     * 查不到报「结构体没有这个成员」（与 [resolveStructMemberAccess] 同一口径）。
     *
     * 这里与「类型名上的成员访问」的区别只在语义措辞：`Point.x` 是**类型级**读字段（要报错），
     * `self.x` 是成员访问的正常形态。
     */
    private fun resolveSelfMemberAccess(expr: Expr.Get, scope: Scope) {
        val structDefId = currentStructDefId
        val structSymbol = structDefId?.let { symbolTable.get(it) }
        if (structSymbol == null) {
            error(bundle.get("diag.implicit-self-outside-method")).label(expr, "")
            return
        }
        val memberIdent = expr.field as? Expr.Identifier
        if (memberIdent == null) {
            resolveExpr(expr.field, scope)
            return
        }
        val memberName = (memberIdent.token.literal as? String) ?: memberIdent.token.type.toString()
        val fields = structSymbol.values.get(Symbol.STRUCT_FIELDS_KEY) as? StructFields
        val methods = structSymbol.values.get(Symbol.STRUCT_METHODS_KEY) as? StructMethods
        val fieldDefId = fields?.get(memberName)
        val methodDefId = methods?.get(memberName)
        when {
            fieldDefId != null -> memberIdent.defId = fieldDefId
            methodDefId != null -> memberIdent.defId = methodDefId
            else -> error(bundle.format("diag.no-such-member", structSymbol.name, memberName))
                .label(
                    memberIdent,
                    bundle.format("diag.no-such-member.help", structSymbol.name, memberNamesText(fields, methods)),
                )
        }
    }

    /**
     * 解析一个「主要作为值使用」的表达式：类型名（`Point`、`Color`）作为函数值合法
     * （struct 构造器、枚举变体构造器），因此与 [resolveExpr] 的差别只有一个——
     * 解析不出名字时**不报**「未声明的标识符」，把诊断让给使用它的上下文
     * （如 [resolveStructMemberAccess] 的「不是成员」、类型推断的「当值使用」）。
     */
    private fun resolveOperand(expr: Expr?, scope: Scope) {
        if (expr == null) return
        if (expr is Expr.Identifier) {
            val rewritten = implicitSelfField(expr, scope)
            if (rewritten !== expr) {
                resolveExpr(rewritten, scope)
                return
            }
            val name = (expr.token.literal as? String) ?: expr.token.type.toString()
            scope.lookup(name)?.let { expr.defId = it }
            // 值位置携带的类型实参（`Point<Int>`）是类型名
            expr.typeArgs?.let { args -> for (a in args) resolveAnnotationNames(a, scope) }
            return
        }
        resolveExpr(expr, scope)
    }

    /**
     * `结构体名.成员` 解析：成员在字段表/方法表里查（两张表都不进普通作用域，否则
     * `Point.x` 的 `x` 会被当成未声明的变量名）。
     *
     * 类型名只能用来构造（`Point(...)`）或限定方法（`Point.dist(p)`），**不能**直接从类型上读字段
     * （那是类型级访问，报 [diag.struct-type-field-access]）；字段读取要写到实例上（`p.x`）。
     */
    private fun resolveStructMemberAccess(expr: Expr.Get, structSymbol: Symbol, scope: Scope) {
        val objIdent = expr.obj as? Expr.Identifier
        objIdent?.defId = structSymbol.id
        objIdent?.typeArgs?.let { args ->
            for (arg in args) resolveAnnotationNames(arg, scope)
        }

        val fieldIdent = expr.field as? Expr.Identifier
        if (fieldIdent == null) {
            resolveExpr(expr.field, scope)
            return
        }
        val fieldName = (fieldIdent.token.literal as? String) ?: fieldIdent.token.type.toString()
        val fields = structSymbol.values.get(Symbol.STRUCT_FIELDS_KEY) as? StructFields
        val methods = structSymbol.values.get(Symbol.STRUCT_METHODS_KEY) as? StructMethods
        val fieldDefId = fields?.get(fieldName)
        val methodDefId = methods?.get(fieldName)
        when {
            fieldDefId != null -> {
                fieldIdent.defId = fieldDefId
                error(bundle.format("diag.struct-type-field-access", structSymbol.name, fieldName))
                    .label(
                        fieldIdent,
                        bundle.format("diag.struct-type-field-access.help", structSymbol.name, fieldName),
                    )
            }

            methodDefId != null -> fieldIdent.defId = methodDefId

            else -> error(bundle.format("diag.no-such-member", structSymbol.name, fieldName))
                .label(
                    fieldIdent,
                    bundle.format("diag.no-such-member.help", structSymbol.name, memberNamesText(fields, methods)),
                )
        }
    }

    /** 结构体的全部成员名（字段在前、方法在后），用于「没有这个成员」的诊断 */
    private fun memberNamesText(fields: StructFields?, methods: StructMethods?): String {
        val names = Seq<String>((fields?.names()?.size ?: 0) + (methods?.names()?.size ?: 0))
        fields?.names()?.let { names.addAll(it) }
        methods?.names()?.let { names.addAll(it) }
        return names.toString(", ")
    }

    private fun resolveStructStmt(stmt: Stmt.Struct, scope: Scope) {
        val name = (stmt.name.token.literal as? String) ?: stmt.name.token.type.toString()
        val structSymbol = declare(name, BuiltinType.Unknown, stmt.name.span, scope)
        stmt.defId = structSymbol?.id
        if (structSymbol == null) return

        // 结构体符号登记：类型（Con/App）与构造器类型由 TypeInferencer 构建，
        // 这里只登记成员表、类型参数数量与字段声明表（字段类型由 TypeInferencer 回填）
        structSymbol.values.put(Symbol.STRUCT_KEY, true)
        structSymbol.values.put(Symbol.STRUCT_TYPE_PARAM_COUNT_KEY, stmt.typeParams?.size ?: 0)
        val fieldDefs = StructFields()
        val methodDefs = StructMethods()
        structSymbol.values.put(Symbol.STRUCT_FIELDS_KEY, fieldDefs)
        structSymbol.values.put(Symbol.STRUCT_METHODS_KEY, methodDefs)

        // 泛型形参先绑定：字段类型与方法签名（`v: T`、`-> T`）都要能解析到它
        val structScope = scope.child()
        // 字段默认值专用作用域：**只有类型参数可见**，字段名不在其中——
        // 默认值不能引用同结构体的其它字段（否则字段默认值之间会形成依赖环），
        // 所以 `struct P { x: Num, y: Num = x }` 里的 `x` 报「未声明的标识符」而不是静默通过
        val defaultScope = scope.child()
        stmt.typeParams?.let { typeParams ->
            for (typeParam in typeParams) {
                resolveTypeParam(typeParam, structScope)
                // 默认值作用域里再绑定一次同名类型参数（**另立符号，不回填 typeParam.defId**：
                // 那个 DefId 必须唯一指向 structScope 里的符号，否则字段类型注解 `v: T`
                // 会解析到另一侧符号，类型推断写进去的类型就不是字段看到的那个）
                bindTypeParamName(typeParam, defaultScope)
            }
        }

        val fieldNames = Seq<String>(stmt.fields.size)
        val fieldDefIds = Seq<DefId>(stmt.fields.size)
        val fieldSpans = Seq<Span>(stmt.fields.size)
        val fieldOrigins = Seq<TypeOrigin>(stmt.fields.size)
        for (field in stmt.fields) {
            val fieldName = (field.name.token.literal as? String) ?: field.name.token.type.toString()
            val fieldSymbol = declare(fieldName, BuiltinType.Unknown, field.name.span, structScope)
            if (fieldSymbol == null) continue
            fieldSymbol.values.put(Symbol.STRUCT_FIELD_KEY, true)
            field.defId = fieldSymbol.id
            fieldNames.add(fieldName)
            fieldDefIds.add(fieldSymbol.id)
            fieldSpans.add(field.name.span)
            fieldOrigins.add(TypeOrigin(field.name.span))
            fieldDefs.put(fieldName, fieldSymbol.id)

            // 字段类型注解里的类型名（`x : Array<Int>` 的 `Array` `Int`）
            field.type?.let { type ->
                for (annotation in type.annotations) resolveAnnotationNames(annotation, structScope)
            }
            field.default?.let { resolveExpr(it, defaultScope) }
        }

        // 方法：登记进成员表（与字段共用一份「成员名」命名空间），签名与函数体走常规函数解析。
        // 成员表先把所有方法名登记完，方法之间才能互相调用。
        for (method in stmt.methods) {
            val methodName = (method.name?.literal as? String) ?: method.name?.type?.toString() ?: continue
            if (methodDefs.contains(methodName) || fieldDefs.contains(methodName)) {
                // 字段与方法共用一份「成员名」命名空间：`p.x` 无法区分两种成员，必须唯一
                error(bundle.format("diag.duplicate-definition", methodName))
                    .label(method.name?.span ?: method.span, bundle.get("diag.duplicate-definition.help"))
                continue
            }
            val methodSymbol = symbolTable.declare(methodName, BuiltinType.Fn, method.name?.span ?: method.span)
            methodSymbol.values.put(Symbol.STRUCT_METHOD_KEY, true)
            methodSymbol.values.put(Symbol.STRUCT_METHOD_OWNER_KEY, structSymbol)
            method.defId = methodSymbol.id
            methodDefs.put(methodName, methodSymbol.id)
        }
        for (method in stmt.methods) {
            resolveMethodStmt(method, structScope, structSymbol.id)
        }

        structSymbol.values.put(
            Symbol.STRUCT_FIELD_TABLE_KEY,
            StructFieldTable(fieldNames, fieldDefIds, fieldSpans, fieldOrigins),
        )
    }

    /**
     * 方法声明：保证形参列表首位是接收者 `self`，再按普通函数解析签名与函数体。
     *
     * 用户显式写的 `self` 只认第一个（按声明位置取第一个 [Expr.SelfRef]），多余的逐个报错；
     * 显式 `self` 不在首位时把首个 `self` 挪到首位（保持「`self` 永远是第 0 个形参」的约定，
     * 方法调用点按下标对齐时不必区分用户写没写）。一个都没写时在首位补一个零长度 span 的
     * [Expr.SelfRef]——`self` 本来就不出现在源码里，它没有可指的位置。
     *
     * 方法体内**裸写的字段名**改写成隐式接收者访问的工作由 [resolveExpr] 负责
     * （靠 [currentStructDefId] 认出「这个名字是当前结构体的字段」）。
     */
    private fun resolveMethodStmt(method: Stmt.Fn, structScope: Scope, structDefId: DefId?) {
        val zeroWidth = Span(method.span.index(), method.span.start(), 0)
        // `params` 是 val 属性，只能就地改动它的内容（Parser 给的是可变的 Seq）。
        // 写成 `fn m { }`（连括号都没有）时参数为 null：那本来就不是合法的方法形参列表，
        // 交给调用处的「参数数量不匹配」报，这里不额外造一个空列表掩盖问题
        val params = method.params
        if (params != null) {
            val selfParams = Seq<Expr.SelfRef>(1)
            for (param in params) {
                if (selfRefOf(param) != null) selfParams.add(selfRefOf(param)!!)
            }
            when {
                selfParams.isEmpty -> params.insert(0, Expr.SelfRef(zeroWidth))

                selfRefOf(params.get(0)) == null -> {
                    // 首个 `self` 不在首位：挪到首位，其余形参保持相对顺序
                    params.remove(selfParams.get(0))
                    params.insert(0, selfParams.get(0))
                }
            }
            for ((i, selfParam) in selfParams.withIndex()) {
                if (i == 0) continue
                error(bundle.get("diag.duplicate-self-param")).label(selfParam, "")
            }
            for (param in params) {
                selfRefOf(param)?.instanceDefId = structDefId
            }
        }

        val previous = currentStructDefId
        currentStructDefId = structDefId
        // 方法符号已在成员表里登记过，这里只走「形参/返回值/函数体」的公共解析，不再 declare
        resolveFnSuffix(method, structScope)
        currentStructDefId = previous
    }

    /**
     * 方法体里裸写的字段名 → 隐式接收者访问 `self.字段名`。
     *
     * 只在**当前结构体的成员名**里查（字段名不进入外层作用域，所以 `x` 在方法体之外仍是
     * 「未声明的标识符」）；查到了就改写成 [Expr.Get]，于是字段读取与 `self.字段名`
     * 在类型推断里收敛到同一条路径，方法体也不必额外区分两种写法。
     */
    private fun implicitSelfField(expr: Expr.Identifier, scope: Scope): Expr {
        val structDefId = currentStructDefId ?: return expr
        val symbol = symbolTable.get(structDefId) ?: return expr
        if (symbol.values.get(Symbol.STRUCT_KEY) != true) return expr
        val fields = symbol.values.get(Symbol.STRUCT_FIELDS_KEY) as? StructFields ?: return expr
        val name = (expr.token.literal as? String) ?: return expr
        val fieldDefId = fields.get(name) ?: return expr
        // 字段名被局部变量/形参遮蔽时按普通名字解析（作用域层级更近者优先，Rust 同此行为）
        if (scope.lookup(name) != fieldDefId) return expr
        return implicitSelfMember(expr, structDefId)
    }

    /**
     * 方法体里裸写的**同结构体方法名** → 隐式接收者调用 `self.方法名`。
     *
     * 与 [implicitSelfField] 同一动机：方法名在成员表里、不进入外层作用域，
     * 因此 `fn two() { return one() }` 里的 `one()` 本会报「未声明的标识符」。
     * 只在**调用位置**改写（`isCallee`），因为方法不能当左值：
     * `one = 1` 这种写法仍按「未声明的标识符」报，不会被悄悄改成 `self.one = 1`。
     */
    private fun implicitSelfMethod(expr: Expr.Identifier, scope: Scope, isCallee: Boolean): Expr {
        if (!isCallee) return expr
        val structDefId = currentStructDefId ?: return expr
        val symbol = symbolTable.get(structDefId) ?: return expr
        val methods = symbol.values.get(Symbol.STRUCT_METHODS_KEY) as? StructMethods ?: return expr
        val name = (expr.token.literal as? String) ?: return expr
        val methodDefId = methods.get(name) ?: return expr
        // 方法名**不**绑定在作用域里（只能经成员访问），所以 `scope.lookup` 要么为 null（正常情况），
        // 要么是更近的局部变量/形参绑定了同名——后者按普通名字解析（局部遮蔽成员）
        val local = scope.lookup(name)
        if (local != null && local != methodDefId) return expr
        return implicitSelfMember(expr, structDefId)
    }

    /** 把标识符包成 `self.标识符`（接收者标记由 Resolver 填好所属结构体） */
    private fun implicitSelfMember(expr: Expr.Identifier, structDefId: DefId): Expr {
        val selfRef = Expr.SelfRef(Span.between(expr, expr))
        selfRef.instanceDefId = structDefId
        return Expr.Get(selfRef, expr)
    }

    /**
     * `枚举名.变体` 解析：`obj` 已确认是枚举类型符号，`field` 只在变体表里查，
     * 不进普通作用域（否则 `Option.Some` 的 `Some` 会被当成未声明的变量名）。
     */
    private fun resolveEnumVariantAccess(expr: Expr.Get, enumSymbol: Symbol, scope: Scope) {
        val objIdent = expr.obj as? Expr.Identifier
        objIdent?.defId = enumSymbol.id
        // `Option<Int>.Some`：显式类型实参是类型名，按类型名解析
        objIdent?.typeArgs?.let { args ->
            for (arg in args) resolveAnnotationNames(arg, scope)
        }

        val fieldIdent = expr.field as? Expr.Identifier
        if (fieldIdent == null) {
            resolveExpr(expr.field, scope)
            return
        }
        val fieldName = (fieldIdent.token.literal as? String) ?: fieldIdent.token.type.toString()
        val variants = enumSymbol.values.get(Symbol.ENUM_VARIANTS_KEY) as? EnumVariants
        val variantDefId = variants?.get(fieldName)
        if (variantDefId == null) {
            error(bundle.format("diag.no-such-variant", enumSymbol.name, fieldName))
                .label(
                    fieldIdent,
                    bundle.format("diag.no-such-variant.help", enumSymbol.name, variants?.namesText() ?: ""),
                )
        } else {
            fieldIdent.defId = variantDefId
        }
    }

    /**
     * 若 [expr] 是解析到**具名类型符号**（枚举 / 结构体）的标识符，返回该符号，否则返回 `null`。
     * 只做名称查询（不填 `defId`），供 `类型名.成员` 判定使用。
     */
    private fun typeSymbolOf(expr: Expr, scope: Scope): Symbol? {
        val ident = expr as? Expr.Identifier ?: return null
        val name = (ident.token.literal as? String) ?: return null
        val symbol = scope.lookup(name)?.let { symbolTable.get(it) } ?: return null
        val isEnum = symbol.values.get(Symbol.ENUM_KEY) == true
        val isStruct = symbol.values.get(Symbol.STRUCT_KEY) == true
        return if (isEnum || isStruct) symbol else null
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
                    error(bundle.format("diag.undeclared-type-name", name))
                        .label(expr, bundle.get("diag.undeclared-type-name.help"))
                } else {
                    expr.defId = defId
                }
                // 递归解析类型实参（`Array<Int>` 中的 `Int`、`Array<Array<T>>` 中的内层 `Array`/`T`）
                expr.typeArgs?.let { args -> for (a in args) resolveAnnotationNames(a, scope) }
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
                for (a in expr.args) resolveAnnotationNames(a, scope)
            }

            is Expr.Get -> {
                resolveAnnotationNames(expr.obj, scope)
                resolveAnnotationNames(expr.field, scope)
            }
            // 其他类型表达式暂不处理
            else -> {}
        }
    }

    // ========== 工具 ==========
    /**
     * 从`Identifier` 和 `Annotation(Identifier, ...)` 中取出标识符。
     */
    private fun unwrapIdentifier(expr: Expr): Expr.Identifier? {
        return when (expr) {
            is Expr.Identifier -> expr
            is Expr.Annotation -> expr.expr as? Expr.Identifier
            else -> null
        }
    }

    /**
     * 从 `SelfRef` 和 `Annotation(SelfRef, ...)`（`self : Point`）中取出隐式接收者标记。
     */
    private fun selfRefOf(expr: Expr): Expr.SelfRef? {
        return when (expr) {
            is Expr.SelfRef -> expr
            is Expr.Annotation -> expr.expr as? Expr.SelfRef
            else -> null
        }
    }

    /**
     * 在当前作用域声明一个定义：分配 DefId、登记到符号表、绑定名称
     * 若名称在当前作用域重复，报错并返回 `null`
     */
    private fun declare(name: String, type: Type, span: Span, scope: Scope): Symbol? {
        if (scope.containsLocal(name)) {
            error(bundle.format("diag.duplicate-definition", name))
                .label(span, bundle.get("diag.duplicate-definition.help"))
            return null
        }
        val symbol = symbolTable.declare(name, type, span)
        scope.bind(name, symbol.id)
        return symbol
    }

    private fun error(text: String): SemanticDiag {
        val e = SemanticDiag(text, Diagnostic.DiagLevel.ERROR)
        context.diagHandler.addError(e)
        return e
    }
}
