package mlogix.compiler.passes.parsing

import arc.func.Boolp
import arc.func.Cons
import arc.func.Prov
import arc.struct.Queue
import arc.struct.Seq
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Expr.Get
import mlogix.compiler.ast.Pattern
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.span.Spanned
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.diagnostic.Diagnostic.ParserDiag
import mlogix.util.I18N.bundle
import java.util.*

/**
 * 一个项目 一次构造
 */
class Parser(
    private val lexer: Lexer,
    private val context: CompilerContext,
) {
    private lateinit var sourceFile: SourceFile
    private lateinit var input: InputWindow
    private val prevToken: Token get() = input.prevToken

    /**
     * 尚未闭合的定界符作用域栈（`(` / `[` / `{`），由各解析函数在开闭处 push/pop。
     *
     * 它不参与 AST 构造，只服务两件事：
     * - 报错时知道「正在解析哪个构造、是哪一层括号」（opener 位置 + 构造名）；
     * - 错误恢复时知道「这个闭合符属于哪一层」，从而在本层缺括号时把控制权交还外层，
     *   而不是一路吞到文件结尾。
     *
     * 注意：它与 Resolver 的语义作用域无关；快照回溯只需要记录/恢复栈深度。
     */
    private val scopes = Seq<Scope>(6)

    /**
     * 一个文件 一次调用
     * 联动重置lexer，之后由 InputWindow 按需实时调用 lexer.scanToken() 扫描 Token
     */
    fun parse(sourceFile: SourceFile): Stmt {
        this.sourceFile = sourceFile
        lexer.reset(sourceFile)
        input = InputWindow()
        scopes.clear()
        return program()
    }

    // ========== 主要解析部分 ==========
    // ---------- Stmt ----------
    private fun program(): Stmt {
        val stmts = Seq<Stmt>()

        while (!isAtEnd) {
            val before = lookAhead(0).span.start
            val stmt = statement()
            if (stmt != null) {
                stmts.add(stmt)
            }
            // 防御：非 EOF 处不允许原地打转（作用域栈万一泄漏，也不至于死循环）
            if (lookAhead(0).span.start == before && !isAtEnd) next()
        }

        return Stmt.Program(Span(sourceFile.index, 0, sourceFile.length()), stmts)
    }

    private fun statement(): Stmt? {
        // 换行后紧跟闭合符时，换行不属于任何语句
        if (lookAhead(0).type == TokenType.NEWLINE && isCloser(lookAhead(1).type)) next()
        val token = lookAhead(0)
        if (TokenType.CLOSERS.contains(token.type)) {
            // 闭合符不是语句：无主的报「多余」，有主的留给外层作用域闭合，这里返回空语句
            if (findScopeByCloser(token.type) == null) reportUnmatchedCloser(next())
            return null
        }
        return when {
            check(TokenType.USE) -> useStmt()
            check(TokenType.IF) -> ifStmt()
            check(TokenType.MATCH) -> matchStmt()
            check(TokenType.FOR) -> forStmt(null)
            check(TokenType.WHILE) -> whileStmt(null)
            check(TokenType.FN) -> fnStmt()
            check(TokenType.LBRACE) -> block()
            check(TokenType.BREAK) -> breakStmt()
            check(TokenType.CONTINUE) -> continueStmt()
            check(TokenType.RETURN) -> returnStmt()
            check(TokenType.SET) -> setStmt()
            check(TokenType.STRUCT) -> structStmt()
            check(TokenType.ENUM) -> enumStmt()

            else -> {
                loopStmtWithLabel() ?: exprStmt()
            }
        }
    }

    private fun useStmt(): Stmt? {
        val start = next() // consume 'use'
        val item = useItem() ?: return null
        val useStmt = Stmt.Use(between(start, item), item)
        if (!consumeStmtEnd(useStmt)) recoverByTokenTree(TokenType.RECOVERY)
        return useStmt
    }

    private fun useItem(): Stmt.Use.UseItem? {
        val path = Seq<Expr.Identifier>()
        while (true) {
            when {
                check(TokenType.IDENTIFIER) -> {
                    val id = next()
                    path.add(Expr.Identifier(id))
                    if (!match(TokenType.DOT)) {
                        return Stmt.Use.Single(between(path[0], prevToken), path)
                    }
                    if (isStmtEnd) break
                    continue
                }

                check(TokenType.STAR) -> {
                    return Stmt.Use.All(if (path.isEmpty) next().span else between(path[0], next()), path)
                }

                check(TokenType.STAR_STAR) -> {
                    return Stmt.Use.Recursion(if (path.isEmpty) next().span else between(path[0], next()), path)
                }

                check(TokenType.LBRACE) -> {
                    val lbrace = next()
                    val scope = openScope(ScopeKind.USE_LIST, lbrace)

                    val items = Seq<Stmt.Use.UseItem>()
                    while (true) {
                        if (closeIfMatched(scope)) {
                            next()
                            break
                        }
                        if (isAtEnd) {
                            reportMissingCloser(scope, lookAhead(0))
                            break
                        }
                        if (isCloser(lookAhead(0).type)) {
                            if (abortOnForeignCloser(scope)) break
                            continue
                        }
                        val item = useItem()
                        if (item == null) {
                            // 列表项解析失败：本层无法继续，报缺失闭合符后交还控制权
                            reportMissingCloser(scope, lookAhead(0))
                            break
                        }
                        items.add(item)
                        match(TokenType.COMMA)
                    }
                    return Stmt.Use.Multi(between((if (path.isEmpty) lbrace else path[0]), prevToken), path, items)
                }

                else -> break
            }
        }
        error(bundle.get("diag.miss-use-item"))
            .label(lookAhead(0), "")
        recoverByTokenTree(TokenType.RECOVERY)
        return null
    }

    private fun block(): Stmt {
        val lBrace = next()
        val scope = openScope(ScopeKind.BLOCK, lBrace)

        val stmts = Seq<Stmt>()
        while (true) {
            if (closeIfMatched(scope)) {
                val rBrace = next()
                return Stmt.Block(between(lBrace, rBrace), stmts)
            }
            if (isAtEnd) {
                reportMissingCloser(scope, lookAhead(0))
                return Stmt.Block(between(lBrace, prevToken), stmts)
            }
            // 出现闭合符：要么属于外层（本层缺 `}`，提前返回），要么是多余的（报告后丢弃）
            if (isCloser(lookAhead(0).type)) {
                if (abortOnForeignCloser(scope)) return Stmt.Block(between(lBrace, prevToken), stmts)
                continue
            }
            statement()?.let { stmts.add(it) }
        }
    }

    private fun ifStmt(): Stmt? {
        val start = next()

        val condition = expression()
        if (condition is Expr.Dummy) {
            recoverByTokenTree(TokenType.RECOVERY)
            return null
        }

        if (expect(TokenType.LBRACE) == null) {
            return Stmt.If(between(start, condition), condition, null, null)
        }
        val thenBranch = block()

        var elseBranch: Stmt? = null
        if (check(TokenType.ELIF)) {
            elseBranch = ifStmt()
        } else if (match(TokenType.ELSE)) {
            if (expect(TokenType.LBRACE) == null) {
                return Stmt.If(between(start, condition), condition, thenBranch, null)
            }
            elseBranch = block()
        }

        return Stmt.If(between(start, prevToken), condition, thenBranch, elseBranch)
    }

    private fun matchStmt(): Stmt? {
        val start = next()

        val scrutinee = expression()
        if (scrutinee is Expr.Dummy) {
            recoverByTokenTree(TokenType.RECOVERY)
            return null
        }

        val lBrace = consume(TokenType.LBRACE)
        if (lBrace == null) {
            error(bundle.get("diag.miss-match-brace"))
                .label(start, bundle.get("diag.stmt-start"))
                .label(lookAhead(0), bundle.get("diag.current"))
            return Stmt.Match(between(start, scrutinee), scrutinee, null)
        }
        val scope = openScope(ScopeKind.MATCH_BODY, lBrace)

        val branches = Seq<Stmt.Match.MatchBranch>()
        while (true) {
            // 分支之间允许任意换行（`check` 只在「位于换行后的下一个 token 是目标类型」时才跳过换行）
            while (lookAhead(0).type == TokenType.NEWLINE) next()

            if (closeIfMatched(scope)) {
                next()
                break
            }
            if (isAtEnd) {
                reportMissingCloser(scope, lookAhead(0))
                return Stmt.Match(
                    between(start, (if (branches.isEmpty) start else branches[branches.size - 1])),
                    scrutinee,
                    branches,
                )
            }
            if (isCloser(lookAhead(0).type)) {
                if (abortOnForeignCloser(scope)) {
                    return Stmt.Match(
                        between(start, (if (branches.isEmpty) start else branches[branches.size - 1])),
                        scrutinee,
                        branches,
                    )
                }
                continue
            }
            val pattern = pattern()
            if (pattern == null) {
                // 模式解析失败：跳过这一整个分支（`-> 分支体`），后面的分支仍能正常解析，避免级联报错
                when (recoverByTokenTree(EnumSet.of(TokenType.ARROW, TokenType.NEWLINE, TokenType.RBRACE))) {
                    TokenType.ARROW -> {
                        next()
                        statement()
                    }

                    else -> Unit
                }
                continue
            }

            if (check(TokenType.ARROW)) {
                next()
            } else {
                // 缺 `->`：若当前已是闭合符（例如模式载荷缺 `)` 已报过错），直接跳过该分支，避免级联
                if (!isCloser(lookAhead(0).type)) {
                    error(bundle.format("diag.miss-token", TokenType.ARROW))
                        .label(lookAhead(0), TokenType.ARROW.toString())
                }
                when (recoverByTokenTree(EnumSet.of(TokenType.NEWLINE, TokenType.RBRACE))) {
                    TokenType.EOF -> {
                        reportMissingCloser(scope, lookAhead(0))
                        return Stmt.Match(
                            between(start, (if (branches.isEmpty) start else branches[branches.size - 1])),
                            scrutinee,
                            branches,
                        )
                    }

                    else -> continue
                }
            }
            val body = statement()
            branches.add(Stmt.Match.MatchBranch(between(pattern, prevToken), pattern, body))
        }
        return Stmt.Match(
            between(start, prevToken),
            scrutinee,
            branches,
        )
    }

    /**
     * 分支模式：
     * - `_` 通配符；
     * - `枚举名.变体` / `枚举名.变体(模式, ...)`（载荷按声明顺序，可嵌套）；
     * - 裸标识符 → 绑定新变量（Rust 风格：要匹配变体必须写全 `枚举名.变体`）。
     *
     * 字面量/元组/范围/or/guard 模式尚未支持，命中时给出明确提示（见 `diag.miss-pattern.help`）。
     *
     * @return 解析失败返回 null（已报错并推进）
     */
    private fun pattern(): Pattern? {
        if (!check(TokenType.IDENTIFIER)) {
            error(bundle.format("diag.miss-pattern", lookAhead(0).type))
                .label(lookAhead(0), "")
                .help(bundle.get("diag.miss-pattern.help"))
            // 保证推进，避免调用方死循环
            if (!isAtEnd) next()
            return null
        }

        val id = next()
        val name = (id.literal as? String) ?: id.type.toString()

        // `枚举名.变体` / `枚举名.变体(...)`
        if (check(TokenType.DOT)) {
            val dot = next()
            val variant = consume(TokenType.IDENTIFIER) {
                error(bundle.get("diag.miss-variant-pattern-name"))
                    .label(dot, bundle.get("diag.variant-pattern-start"))
                    .label(lookAhead(0), bundle.get("diag.current"))
            } ?: return null
            val path = Get(Expr.Identifier(id), Expr.Identifier(variant))
            val args = if (check(TokenType.LPAREN)) patternArgs() else Seq(0)
            return Pattern.Variant(between(id, prevToken), path, args)
        }

        // `_` 通配符
        if (name == "_") return Pattern.Wildcard(id.span)

        // 裸标识符 → 绑定
        return Pattern.Binding(id.span, Expr.Identifier(id))
    }

    /**
     * 变体模式的载荷列表，调用前须 `check(TokenType.LPAREN)`。
     * 分隔符与语言其它列表一致：`,` 或换行可省略（空格亦可）。
     */
    private fun patternArgs(): Seq<Pattern> {
        val lParen = next()
        val scope = openScope(ScopeKind.PATTERN_ARGS, lParen)
        val args = Seq<Pattern>(3)
        while (true) {
            if (closeIfMatched(scope)) {
                next()
                return args
            }
            if (isAtEnd) {
                reportMissingCloser(scope, lookAhead(0))
                return args
            }
            if (abortOnForeignCloser(scope)) return args
            // 语句起始 token 不可能出现在模式实参位置：本层缺 `)`
            if (isStatementStart(lookAhead(0).type)) {
                reportMissingCloser(scope, lookAhead(0))
                return args
            }
            val before = lookAhead(0).span.start
            val arg = pattern()
            if (arg != null) args.add(arg)
            // 分隔符：`,` / 换行 / 空格皆可；若这一轮没有推进，强制推进一格防止死循环
            match(TokenType.COMMA)
            matchStmtEnd()
            if (lookAhead(0).span.start == before && !check(scope.closer)) next()
        }
    }

    private fun forStmt(flag: Expr.Identifier?): Stmt? {
        val head = next()
        val start = flag ?: head
        var varDecl: Expr.Identifier? = null
        var expr: Expr?

        if (check(TokenType.IDENTIFIER)) {
            // for id
            varDecl = Expr.Identifier(next())
            expr = if (match("in")) {
                // for id in expr
                expression()
            } else {
                // for id
                null
            }
        } else {
            // for repeatNum
            expr = expression()
        }

        if (expr is Expr.Dummy) {
            recoverByTokenTree(TokenType.RECOVERY)
            return null
        }

        if (expect(TokenType.LBRACE) { e: Diagnostic -> e.label(head, bundle.get("diag.for-stmt")) } == null) {
            // 缺 `{`：只跳到行尾或外层 `}`，不吞掉后续语句
            if (!isStmtEnd) recoverByTokenTree(EnumSet.of(TokenType.RBRACE, TokenType.NEWLINE))
            return Stmt.For(between(start, expr ?: varDecl!!), flag, varDecl, expr, null)
        }
        val body = block()

        return Stmt.For(between(start, body), flag, varDecl, expr, body)

    }

    private fun whileStmt(flag: Expr.Identifier?): Stmt? {
        val head = next()
        val start = flag ?: head

        val expr = expression()
        if (expr is Expr.Dummy) {
            recoverByTokenTree(TokenType.RECOVERY)
            return null
        }

        if (expect(TokenType.LBRACE) { e: Diagnostic -> e.label(head, bundle.get("diag.while-stmt")) } == null) {
            // 缺 `{`：只跳到行尾或外层 `}`，不吞掉后续语句
            if (!isStmtEnd) recoverByTokenTree(EnumSet.of(TokenType.RBRACE, TokenType.NEWLINE))
            return Stmt.While(between(start, expr), flag, expr, null)
        }
        val body = block()
        return Stmt.While(between(start, body), flag, expr, body)

    }


    private fun loopStmtWithLabel(): Stmt? {
        val snapshot = createSnapshot()
        if (check(TokenType.IDENTIFIER)) {
            val flag = next()
            if (!isStmtEnd && match(TokenType.COLON)) {
                if (check(TokenType.FOR)) return forStmt(Expr.Identifier(flag))
                if (check(TokenType.WHILE)) return whileStmt(Expr.Identifier(flag))
            }
            if (check(TokenType.COLON)) {
                // 冒号跟label不在同一行
                error(bundle.get("diag.loop-label-same-line"))
                    .label(flag, bundle.get("diag.loop-label"))
                    .label(next(), "")
                if (check(TokenType.FOR)) return forStmt(Expr.Identifier(flag))
                if (check(TokenType.WHILE)) return whileStmt(Expr.Identifier(flag))
            }
        }
        restoreSnapshot(snapshot)
        return null
    }

    private fun fnStmt(): Stmt.Fn {
        val start = next()

        val name = if (check(TokenType.IDENTIFIER)) next() else null

        var typeParams: Seq<Expr.Identifier>? = null
        if (check(TokenType.LESS)) {
            val result = generics()
            if (result is GenericsResult.Success) {
                result.checkRemaining()
                result.checkEmpty()
                if (result.generics.size != 0) {
                    typeParams = result.generics
                }
            }
        }

        var parameters: Seq<Expr>? = null
        if (check(TokenType.LPAREN)) {
            val lParen = next()
            val scope = openScope(ScopeKind.FUNC_PARAMS, lParen)

            var isSeparatorOptional = false
            parameters = seq(
                {
                    consume(TokenType.IDENTIFIER) {
                        error(bundle.get("diag.miss-ident-as-param")).label(lookAhead(0))
                    }?.let {
                        val expr = annotation(Expr.Identifier(it))
                        isSeparatorOptional = expr !is Expr.TypeAnnotation
                        return@let expr
                    }
                },
                EnumSet.of(TokenType.COMMA, TokenType.NEWLINE),
                { isSeparatorOptional },
                {
                    error(bundle.get("diag.miss-param-separator"))
                        .label(lookAhead(0))
                        .label(lParen, bundle.get("diag.param-start"))
                },
                TokenType.RPAREN,
                true,
                scope = scope,
            )
            // 形参表缺 `)` 且遇到外层闭合符：整个函数声明在此结束，不再解析返回值与函数体
            if (scope.aborted) {
                return Stmt.Fn(between(start, prevToken), name, typeParams, parameters, Seq(0), null)
            }
        }

        val results = Seq<Expr>(3)
        if (check(TokenType.ARROW) || check(TokenType.COLON)) {
            val arrow = next()
            if (arrow.type == TokenType.COLON) {
                error(bundle.get("diag.expected-arrow-but-colon"))
                    .label(arrow)
            }
            if (check(TokenType.QUESTION_MARK)) {
                results.add(Expr.Identifier(Token(next().span, TokenType.IDENTIFIER, "Null")))
                if (check(TokenType.COMMA)) {
                    warning(bundle.get("diag.redundant-comma"))
                        .label(next(), "")
                }
            }

            var isSeparatorOptional = false
            results.add(
                seq(
                    {
                        consume(TokenType.IDENTIFIER) {
                            error(bundle.get("diag.miss-ident-as-result")).label(lookAhead(0))
                        }?.let {
                            // 名字后可以跟类型参数（`-> Array<Int>`）；写法与类型注解里的类型表达式一致
                            val expr = annotation(identifierWithGenerics(it))
                            isSeparatorOptional = expr !is Expr.TypeAnnotation
                            return@let expr
                        }
                    },
                    EnumSet.of(TokenType.COMMA, TokenType.NEWLINE),
                    { isSeparatorOptional },
                    {
                        error(bundle.get("diag.miss-result-separator"))
                            .label(lookAhead(0))
                            .label(arrow, bundle.get("diag.result-start"))
                    },
                    TokenType.LBRACE,
                    false,
                )
            )
            if (results.isEmpty) {
                error(bundle.get("diag.miss-result-decl-after-arrow"))
                    .label(arrow, "")
            }
        }

        if (check(TokenType.LBRACE)) {
            val body = block()
            return Stmt.Fn(between(start, body), name, typeParams, parameters, results, body)
        } else {
            return Stmt.Fn(between(start, prevToken), name, typeParams, parameters, results, null)
        }
    }

    private fun breakStmt(): Stmt {
        val start = next()
        if (matchStmtEnd()) return Stmt.Break(start.span, null)
        val flag = consume(TokenType.IDENTIFIER) ?: return Stmt.Break(start.span, null)
        val breakStmt = Stmt.Break(between(start, flag), Expr.Identifier(flag))
        if (!consumeStmtEnd(breakStmt)) recoverByTokenTree(TokenType.RECOVERY)
        return breakStmt
    }

    private fun continueStmt(): Stmt {
        val start = next()
        if (matchStmtEnd()) return Stmt.Continue(start.span, null)
        val flag = consume(TokenType.IDENTIFIER) ?: return Stmt.Continue(start.span, null)
        val continueStmt = Stmt.Continue(between(start, flag), Expr.Identifier(flag))
        if (!consumeStmtEnd(continueStmt)) recoverByTokenTree(TokenType.RECOVERY)
        return continueStmt
    }

    private fun returnStmt(): Stmt {
        val start = next()
        if (matchStmtEnd()) return Stmt.Return(start.span, null)
        val expr = expression()
        val returnStmt = Stmt.Return(between(start, expr), expr)
        if (expr is Expr.Dummy || !consumeStmtEnd(returnStmt)) recoverByTokenTree(TokenType.RECOVERY)
        return returnStmt
    }

    private fun setStmt(): Stmt? {
        val start = next()

        if (isStmtEnd) {
            error(bundle.get("diag.set-without-var"))
                .label(lookAhead(0), "")
                .label(start, "")
            return null
        }
        var expr = expression()
        if (expr is Expr.Identifier) {
            expr = annotation(expr)
        }
        if (expr is Expr.Dummy) {
            when (recoverByTokenTree(TokenType.RECOVERY)) {
                in TokenType.ASSIGNS -> {}

                else -> return null
            }
        }

        val assignStmt = assignStmt(expr)
        if (assignStmt == null) {
            val setVarStmt = Stmt.SetVar(between(start, expr), expr, null)
            if (!consumeStmtEnd(setVarStmt)) recoverByTokenTree(TokenType.RECOVERY)
            return setVarStmt
        } else {
            val setVarStmt = Stmt.SetVar(between(start, assignStmt), expr, assignStmt)
            if (assignStmt.value is Expr.Dummy || !consumeStmtEnd(setVarStmt)) {
                recoverByTokenTree(TokenType.RECOVERY)
            }
            return setVarStmt
        }
    }

    private fun structStmt(): Stmt? {
        val start = next()

        val name = consume(TokenType.IDENTIFIER) {
            error(bundle.get("diag.miss-struct-name"))
                .label(lookAhead(0))
        }
        if (name == null) {
            recoverByTokenTree(TokenType.RECOVERY)
            return null
        }
        var typeParams: Seq<Expr.Identifier>? = null
        if (check(TokenType.LESS)) {
            val result = generics()
            if (result is GenericsResult.Success) {
                result.checkRemaining()
                result.checkEmpty()
                if (result.generics.size != 0) {
                    typeParams = result.generics
                }
            }
        }

        var end: Token = name
        val fields = Seq<Stmt.Struct.StructField>(6)
        val methods = Seq<Stmt.Fn>(3)
        if (check(TokenType.LBRACE)) {
            end = next()
            val scope = openScope(ScopeKind.STRUCT_BODY, end)
            var isCommaOptional = true
            while (true) {
                if (closeIfMatched(scope)) {
                    end = next()
                    break
                }
                if (isAtEnd) {
                    reportMissingCloser(scope, lookAhead(0))
                    end = prevToken
                    break
                }
                if (isCloser(lookAhead(0).type)) {
                    end = prevToken
                    if (abortOnForeignCloser(scope)) break
                    continue
                }
                if (check(TokenType.IDENTIFIER)) {
                    if (!isCommaOptional) {
                        error(bundle.get("diag.miss-struct-field-separator"))
                            .label(lookAhead(0))
                    }
                    structField()?.let { fields.add(it) }
                    isCommaOptional = match(TokenType.COMMA) || matchStmtEnd()

                } else if (check(TokenType.FN)) {
                    methods.add(fnStmt())
                    isCommaOptional = true

                } else {
                    error(bundle.get("diag.miss-struct-item"))
                        .label(lookAhead(0))
                    when (recoverByTokenTree(TokenType.RECOVERY)) {
                        TokenType.RBRACE -> {
                            closeIfMatched(scope)
                            end = next()
                            break
                        }

                        TokenType.FN -> methods.add(fnStmt())
                        else -> {
                            // 遇到新语句/其它同步点：本结构体缺 `}`，报错并交还外层
                            reportMissingCloser(scope, lookAhead(0))
                            end = prevToken
                            break
                        }
                    }
                }
            }
        }
        return Stmt.Struct(between(start, end), Expr.Identifier(name), typeParams, fields, methods)
    }

    /**
     * 结构体字段声明 `名字 [: 类型] [= 默认值]`，调用前须 `check(TokenType.IDENTIFIER)`。
     *
     * 类型与默认值至少要写出一个：两者都不写就没有任何信息可以推断字段类型
     * （`struct Point { x }` 报 [diag.miss-struct-field-type]），此时仍产出字段节点，
     * 让后续阶段按「无类型无默认值」继续走，不额外制造级联报错。
     *
     * 默认值用 [assignStmt] 解析，因此 `+=` 这类复合赋值也会被解析出来——它不是合法的字段默认值，
     * 这里只取 `=`（[TokenType.ASSIGN]）的形态，其它算符按「没有默认值」处理并报错。
     */
    private fun structField(): Stmt.Struct.StructField? {
        val fieldName = consume(TokenType.IDENTIFIER) {
            error(bundle.get("diag.miss-struct-field-name"))
                .label(lookAhead(0))
        } ?: return null
        val ident = Expr.Identifier(fieldName)

        val annotation = annotation(ident)
        if (annotation is Expr.Dummy) return Stmt.Struct.StructField(annotation.span, ident, null, null)
        val type = annotation as? Expr.TypeAnnotation

        var default: Expr? = null
        val assign = assignStmt(ident)
        if (assign != null) {
            when (assign.operator.type) {
                TokenType.ASSIGN -> default = assign.value

                else -> {
                    error(bundle.get("diag.struct-field-default-operator"))
                        .label(assign.operator, "")
                }
            }
        }

        if (type == null && default == null) {
            error(bundle.get("diag.miss-struct-field-type"))
                .label(ident, bundle.get("diag.miss-struct-field-type.help"))
        }

        val end: Spanned = default ?: type ?: ident
        return Stmt.Struct.StructField(between(fieldName, end), ident, type, default)
    }

    /**
     * 枚举声明（变体用 `.` 访问）：
     * ```
     * enum Option<T> {
     *     None
     *     Some(T)
     *     Point { x: Num, y: Num }
     * }
     * ```
     * 变体之间以换行或 `,` 分隔（与结构体字段一致，两者其一即可）。
     */
    private fun enumStmt(): Stmt? {
        val start = next()

        val name = consume(TokenType.IDENTIFIER) {
            error(bundle.get("diag.miss-enum-name"))
                .label(lookAhead(0))
        }
        if (name == null) {
            recoverByTokenTree(TokenType.RECOVERY)
            return null
        }

        var typeParams: Seq<Expr.Identifier>? = null
        if (check(TokenType.LESS)) {
            val result = generics()
            if (result is GenericsResult.Success) {
                result.checkRemaining()
                result.checkEmpty()
                if (result.generics.size != 0) {
                    typeParams = result.generics
                }
            }
        }

        var end: Token = name
        val variants = Seq<Stmt.Enum.EnumVariant>(4)
        if (check(TokenType.LBRACE)) {
            end = next()
            val scope = openScope(ScopeKind.ENUM_BODY, end)
            var isSeparatorOptional = true
            while (true) {
                if (closeIfMatched(scope)) {
                    end = next()
                    break
                }
                if (isAtEnd) {
                    reportMissingCloser(scope, lookAhead(0))
                    end = prevToken
                    break
                }
                if (isCloser(lookAhead(0).type)) {
                    end = prevToken
                    if (abortOnForeignCloser(scope)) break
                    continue
                }
                if (check(TokenType.IDENTIFIER)) {
                    if (!isSeparatorOptional) {
                        error(bundle.get("diag.miss-variant-separator"))
                            .label(lookAhead(0))
                    }
                    enumVariant()?.let { variants.add(it) }
                    // 变体已被消耗：无论成功与否都推进分隔符，避免死循环
                    isSeparatorOptional = match(TokenType.COMMA) || matchStmtEnd()
                } else {
                    error(bundle.get("diag.miss-enum-item"))
                        .label(lookAhead(0))
                    when (recoverByTokenTree(TokenType.RECOVERY)) {
                        TokenType.RBRACE -> {
                            closeIfMatched(scope)
                            end = next()
                            break
                        }

                        TokenType.IDENTIFIER -> {
                            enumVariant()?.let { variants.add(it) }
                            isSeparatorOptional = match(TokenType.COMMA) || matchStmtEnd()
                        }

                        else -> {
                            // 遇到其它同步点：本枚举缺 `}`，报错并交还外层
                            reportMissingCloser(scope, lookAhead(0))
                            end = prevToken
                            break
                        }
                    }
                }
            }
        } else {
            error(bundle.get("diag.miss-enum-brace"))
                .label(start, bundle.get("diag.stmt-start"))
                .label(lookAhead(0), bundle.get("diag.current"))
        }
        return Stmt.Enum(between(start, end), Expr.Identifier(name), typeParams, variants)
    }

    /**
     * 枚举变体，调用前须 `check(TokenType.IDENTIFIER)`：
     * - 单元变体 `Red`
     * - 元组变体 `Rgb(Num, Num, Num)`：载荷是类型表达式
     * - 结构体变体 `Named { name: Str, alpha: Num }`：载荷是 `字段名 : 类型` 注解
     */
    private fun enumVariant(): Stmt.Enum.EnumVariant? {
        val variantName = consume(TokenType.IDENTIFIER) {
            error(bundle.get("diag.miss-variant-name"))
                .label(lookAhead(0))
        }
        if (variantName == null) {
            recoverByTokenTree(TokenType.RECOVERY)
            return null
        }
        val ident = Expr.Identifier(variantName)

        // 元组变体 `Rgb(Num, Num, Num)`
        if (check(TokenType.LPAREN)) {
            val lParen = next()
            val scope = openScope(ScopeKind.VARIANT_FIELDS, lParen)
            val fields = seq(
                { identifier() },
                EnumSet.of(TokenType.COMMA, TokenType.NEWLINE),
                { false },
                {
                    error(bundle.get("diag.miss-variant-field-separator"))
                        .label(lookAhead(0))
                        .label(lParen, bundle.get("diag.variant-field-start"))
                },
                TokenType.RPAREN,
                true,
                scope = scope,
            )
            return Stmt.Enum.EnumVariant.Tuple(between(variantName, prevToken), ident, fields)
        }

        // 结构体变体 `Named { name: Str, alpha: Num }`
        if (check(TokenType.LBRACE)) {
            val lBrace = next()
            val scope = openScope(ScopeKind.VARIANT_BODY, lBrace)
            val fields = Seq<Expr>(4)
            var isCommaOptional = true
            while (true) {
                if (closeIfMatched(scope)) {
                    val rBrace = next()
                    return Stmt.Enum.EnumVariant.Struct(between(variantName, rBrace), ident, fields)
                }
                if (isAtEnd) {
                    reportMissingCloser(scope, lookAhead(0))
                    return Stmt.Enum.EnumVariant.Struct(between(variantName, prevToken), ident, fields)
                }
                if (isCloser(lookAhead(0).type)) {
                    if (abortOnForeignCloser(scope)) {
                        return Stmt.Enum.EnumVariant.Struct(between(variantName, prevToken), ident, fields)
                    }
                    continue
                }
                if (check(TokenType.IDENTIFIER)) {
                    if (!isCommaOptional) {
                        error(bundle.get("diag.miss-variant-field-separator"))
                            .label(lookAhead(0))
                    }
                    val fieldName = next()
                    val field = annotation(Expr.Identifier(fieldName))
                    if (field !is Expr.TypeAnnotation) {
                        error(bundle.get("diag.miss-variant-field-type"))
                            .label(fieldName, "")
                    }
                    fields.add(field)
                    isCommaOptional = match(TokenType.COMMA) || matchStmtEnd()

                } else {
                    error(bundle.get("diag.miss-variant-field"))
                        .label(lookAhead(0))
                    when (val type = recoverByTokenTree(EnumSet.of(TokenType.COMMA, TokenType.NEWLINE, TokenType.RBRACE))) {
                        TokenType.RBRACE -> {
                            closeIfMatched(scope)
                            val rBrace = next()
                            return Stmt.Enum.EnumVariant.Struct(between(variantName, rBrace), ident, fields)
                        }

                        TokenType.EOF -> {
                            reportMissingCloser(scope, lookAhead(0))
                            return Stmt.Enum.EnumVariant.Struct(
                                between(variantName, prevToken),
                                ident,
                                fields
                            )
                        }

                        else -> {
                            if (isCloser(type) || isStatementStart(type)) {
                                // 遇到外层闭合符或新语句：本变体缺 `}`，交还控制权给外层
                                reportMissingCloser(scope, lookAhead(0))
                                return Stmt.Enum.EnumVariant.Struct(
                                    between(variantName, prevToken),
                                    ident,
                                    fields
                                )
                            }
                            next()
                            isCommaOptional = true
                        }
                    }
                }
            }
        }

        // 单元变体 `Red`
        return Stmt.Enum.EnumVariant.Unit(variantName.span, ident)
    }

    private fun exprStmt(): Stmt? {
        val expr = expression()
        if (expr is Expr.Dummy) {
            recoverByTokenTree(TokenType.RECOVERY)
            return null
        }

        val assignStmt = assignStmt(expr)

        if (assignStmt == null) {
            val exprStmt = Stmt.ExprStmt(expr.span, expr)
            if (!consumeStmtEnd(exprStmt)) recoverByTokenTree(TokenType.RECOVERY)
            return exprStmt
        } else {
            if (assignStmt.value is Expr.Dummy || !consumeStmtEnd(assignStmt)) {
                recoverByTokenTree(TokenType.RECOVERY)
            }
            return assignStmt
        }
    }

    /**
     * 解析 赋值 `= ...` 或 复合赋值 `+= ...`
     * @param expr `=`或复合赋值运算符前的表达式
     * @return 没有`=`或者复合赋值运算符时返回null;有时返回AssignStmt并推进
     */
    private fun assignStmt(expr: Expr): Stmt.Assign? {
        if (isStmtEnd) {
            if (check(TokenType.ASSIGNS)) {
                error(bundle.get("diag.assign-missing-lhs"))
                    .label(next(), "")
                recoverByTokenTree(TokenType.RECOVERY)
            }
            return null
        }
        if (check(TokenType.ASSIGN)) {
            val operator = next()
            if (isStmtEnd) {
                error(bundle.get("diag.assign-missing-rhs"))
                    .label(next(), "")
                recoverByTokenTree(TokenType.RECOVERY)
                return null
            }
            val value = expression()
            return Stmt.Assign(between(expr, value), expr, operator, value)

        } else if (check(TokenType.ASSIGNS)) {
            val operator = next()
            if (isStmtEnd) {
                error(bundle.get("diag.assign-missing-rhs"))
                    .label(next(), "")
                recoverByTokenTree(TokenType.RECOVERY)
                return null
            }
            val right = expression()
            return Stmt.Assign(
                between(expr, right),
                expr,
                operator,
                right
            )
        }
        return null
    }

    // ---------- Expr ----------
    private fun expression(): Expr = or()

    private fun or(): Expr {
        var expr = and()
        if (expr is Expr.Dummy) return expr

        var errorRight: Expr? = null

        while (check(TokenType.OR_OR)) {
            val operator = next()

            val right = and()
            if (right is Expr.Dummy) return Expr.Dummy(between(expr, right))

            if (right is Expr.Binary && right.operator.type == TokenType.AND_AND) {
                errorRight = right
            }
            expr = Expr.Binary(expr, operator, right)
        }
        errorRight?.let {
            error(bundle.get("diag.ambiguous-logic"))
                .label(Span.between(expr, errorRight), "")
        }

        return expr
    }

    private fun and(): Expr {
        var expr = equality()
        if (expr is Expr.Dummy) return expr

        while (check(TokenType.AND_AND)) {
            val operator = next()

            val right = equality()
            if (right is Expr.Dummy) return Expr.Dummy(between(expr, right))

            if (right is Expr.Binary && right.operator.type == TokenType.OR_OR) {
                error(bundle.get("diag.ambiguous-logic"))
                    .label(Span.between(expr, right), "")
                // expr && right.left || right.right
                // 按照优先级and > or进行重构
                // (expr && right.left) || right.right
                expr = Expr.Binary(
                    Expr.Binary(expr, operator, right.left),
                    right.operator,
                    right.right,
                )
            } else {
                expr = Expr.Binary(expr, operator, right)
            }
        }
        return expr
    }

    /**
     * == != === !==
     */
    private fun equality(): Expr {
        var expr = comparison()
        if (expr is Expr.Dummy) return expr

        if (check(TokenType.EQ_OPERATORS)) {
            val operator = next()
            val right = comparison()
            if (right is Expr.Dummy) return Expr.Dummy(between(expr, right))
            expr = Expr.Binary(expr, operator, right)
        }

        return expr
    }

    /**
     * > >= < <=
     */
    private fun comparison(): Expr {
        var expr = range()
        if (expr is Expr.Dummy) return expr

        if (check(TokenType.COMPARISON_OPERATORS)) {
            val operator = next()
            val right = range()
            if (right is Expr.Dummy) return Expr.Dummy(between(expr, right))
            expr = Expr.Binary(expr, operator, right)
        }

        return expr
    }

    private fun range(): Expr {
        // :< ...    := ...
        if (check(TokenType.RANGE_OPERATORS)) {
            val operator = next()

            // :<    :=
            if (!check(TokenType.LITERALS) && !check(TokenType.IDENTIFIER) && !check(TokenType.LPAREN)) {
                return Expr.Range(operator.span, null, operator, null)
            }

            // :< expr    := expr
            val right = addAndSub()
            if (right is Expr.Dummy) return Expr.Dummy(between(operator, right))
            return Expr.Range(between(operator, right), null, operator, right)
        }

        // expr
        var expr = addAndSub()
        if (expr is Expr.Dummy) return expr

        // expr :< ...    expr := ...
        if (!isStmtEnd && check(TokenType.RANGE_OPERATORS)) {
            val operator = next()

            // expr :<    expr :=
            if (!check(TokenType.LITERALS) && !check(TokenType.IDENTIFIER) && !check(TokenType.LPAREN)) {
                return Expr.Range(between(operator, operator), null, operator, null)
            }

            // expr :< expr    expr := expr
            val right = addAndSub()
            if (right is Expr.Dummy) return Expr.Dummy(between(expr, right))
            expr = Expr.Range(between(expr, right), expr, operator, right)
        }

        return expr
    }

    private fun addAndSub(): Expr {
        var expr = mulAndDiv()
        if (expr is Expr.Dummy) return expr

        while (check(TokenType.ADD_SUB_OPERATORS)) {
            val operator = next()
            val right = mulAndDiv()
            expr = Expr.Binary(expr, operator, right)
        }

        return expr
    }

    private fun mulAndDiv(): Expr {
        var expr = pow()
        if (expr is Expr.Dummy) return expr

        while (check(TokenType.MUL_DIV_OPERATORS)) {
            val operator = next()
            val right = pow()
            expr = Expr.Binary(expr, operator, right)
        }

        return expr
    }

    private fun pow(): Expr {
        var expr = unary()
        if (expr is Expr.Dummy) return expr

        if (check(TokenType.STAR_STAR)) {
            val operator = next()
            val right = pow() // 右结合
            expr = Expr.Binary(expr, operator, right)
        }

        return expr
    }

    private fun unary(): Expr {
        if (check(TokenType.UNARY_OPERATORS)) {
            val operator = next()
            val right = unary()
            if (right is Expr.Dummy) return Expr.Dummy(between(operator, right))
            return Expr.Unary(operator, right)
        }

        return suffixExpr()
    }

    private fun suffixExpr(): Expr {
        var expr = primary()
        if (expr is Expr.Dummy) return expr

        while (true) {
            when {
                check(TokenType.LBRACKET) -> { // 对列表的索引或切片
                    val lBracket = next()
                    val scope = openScope(ScopeKind.INDEX, lBracket)

                    val index = expression()
                    if (index is Expr.Dummy) {
                        popScope(scope)
                        return Expr.Dummy(between(expr, lookAhead(0)))
                    }
                    // 外层闭合符到达：本层缺 `]`，整个后缀表达式作废，交还外层
                    if (skipNewlineBeforeBoundary()) {
                        reportMissingCloser(scope, lookAhead(0))
                        return Expr.Dummy(between(expr, lookAhead(0)))
                    }
                    if (isAtEnd) {
                        reportMissingCloser(scope, lookAhead(0))
                        return Expr.Dummy(between(expr, lookAhead(0)))
                    }
                    if (abortOnForeignCloser(scope)) return Expr.Dummy(between(expr, lookAhead(0)))

                    expr = if (expectClose(scope)) {
                        val rBracket = next()
                        Expr.Index(between(lBracket, rBracket), expr, index)
                    } else {
                        Expr.Index(between(lBracket, index), expr, index)
                    }
                    continue
                }

                check(TokenType.LPAREN) -> { // 函数调用
                    val lParen = next()
                    val scope = openScope(ScopeKind.CALL_ARGS, lParen)

                    val args = Seq<Expr>(8)
                    while (true) {
                        if (closeIfMatched(scope)) {
                            expr = Expr.Call(between(expr, next()), expr, args)
                            break
                        }
                        if (skipNewlineBeforeBoundary()) {
                            reportMissingCloser(scope, lookAhead(0))
                            return Expr.Dummy(between(expr, lookAhead(0)))
                        }
                        if (isAtEnd) {
                            reportMissingCloser(scope, lookAhead(0))
                            return Expr.Dummy(between(expr, lookAhead(0)))
                        }
                        if (abortOnForeignCloser(scope)) {
                            return Expr.Dummy(between(expr, lookAhead(0)))
                        }
                        val innerExpr = expression()
                        if (innerExpr is Expr.Dummy) {
                            reportMissingCloser(scope, lookAhead(0))
                            return Expr.Dummy(between(expr, lookAhead(0)))
                        }
                        args.add(innerExpr)
                        match(TokenType.COMMA) // 可选逗号
                    }
                    continue
                }

                check(TokenType.DOT) -> { // 访问类的元素
                    val dot = next()

                    val id =
                        consume(TokenType.IDENTIFIER) {
                            error(bundle.get("diag.get-error")).label(dot)
                        } ?: return expr
                    val field: Expr = Expr.Identifier(id)

                    expr = Get(expr, field)
                    continue
                }
            }

            return expr
        }
    }

    private fun primary(): Expr {
        if (check(TokenType.LITERALS)) {
            return Expr.Literal(next())

        } else if (check(TokenType.LPAREN)) {
            val lParen = next()
            val scope = openScope(ScopeKind.GROUPING, lParen)
            val first = expression()
            if (first is Expr.Dummy) {
                popScope(scope)
                return Expr.Dummy(between(lParen, first))
            }
            // 外层闭合符到达：本层缺 `)`，交还外层
            if (abortOnForeignCloser(scope)) return Expr.Dummy(between(lParen, first))
            if (skipNewlineBeforeBoundary()) {
                reportMissingCloser(scope, lookAhead(0))
                return Expr.Dummy(between(lParen, prevToken))
            }

            // `(a, b, ...)`：元组字面量（多返回值就是元组，`return (1, "s")`）；
            // 没有逗号时保持 `(a)` 的分组语义
            if (check(TokenType.COMMA)) {
                scope.kind = ScopeKind.TUPLE
                val elements = Seq<Expr>(4)
                elements.add(first)
                match(TokenType.COMMA)
                while (true) {
                    if (closeIfMatched(scope)) return Expr.Tuple(between(lParen, next()), elements)
                    if (skipNewlineBeforeBoundary()) {
                        reportMissingCloser(scope, lookAhead(0))
                        return Expr.Tuple(between(lParen, prevToken), elements)
                    }
                    if (isAtEnd) {
                        reportMissingCloser(scope, lookAhead(0))
                        return Expr.Tuple(between(lParen, prevToken), elements)
                    }
                    if (abortOnForeignCloser(scope)) return Expr.Tuple(between(lParen, prevToken), elements)
                    val element = expression()
                    if (element is Expr.Dummy) {
                        reportMissingCloser(scope, lookAhead(0))
                        return Expr.Tuple(between(lParen, prevToken), elements)
                    }
                    elements.add(element)
                    match(TokenType.COMMA) // 允许尾随逗号
                }
            }

            if (expectClose(scope)) {
                next()
                return first
            }
            // 缺 `)`：已报错，保留分组表达式本身
            return first

        } else if (check(TokenType.LBRACE)) {
            val lBrace = next()
            val scope = openScope(ScopeKind.ARRAY, lBrace)
            val elements = seq(
                { expression() },
                EnumSet.of(TokenType.COMMA, TokenType.NEWLINE),
                { true },
                {
                    error(bundle.get("diag.miss-array-separator"))
                        .label(lookAhead(0))
                        .label(lBrace, bundle.get("diag.array-start"))
                },
                TokenType.RBRACE,
                true,
                scope = scope,
            )
            return Expr.Array(between(lBrace, prevToken), elements)
        }

        return identifier()
    }

    private fun identifier(): Expr {
        if (check(TokenType.IDENTIFIER)) {
            val id = next()
            return identifierWithGenerics(id)
        }
        if (!check(TokenType.DUMMY)) {
            error(bundle.format("diag.miss-expression", lookAhead(0).type))
                .label(lookAhead(0), "")
        }
        return Expr.Dummy(next().span)
    }

    /**
     * 已消耗名字 token 后，尝试把紧随其后的类型参数（`Array<Int>`、`Option<Option<Int>>`）
     * 挂到这个标识符上；没有 `<...>` 时就是裸标识符。
     *
     * 供表达式/类型表达式（[identifier]）与**裸写返回值类型**（`-> Array<Int>`）共用：
     * 后者此前只消费名字、丢掉类型参数，于是 `-> Array<Int>` 被当成裸 `Array`（元素类型退化成未知）。
     */
    private fun identifierWithGenerics(id: Token): Expr.Identifier {
        if (!check(TokenType.LESS)) return Expr.Identifier(id)
        val snapshot = createSnapshotWithDiagHandler()
        return when (val result = generics()) {
            is GenericsResult.Success -> {
                result.checkRemaining()
                result.checkEmpty()
                Expr.Identifier(
                    between(id, result.closeAngle.takeFirst(1)),
                    id,
                    result.generics
                )
            }

            is GenericsResult.NotGeneric -> {
                restoreSnapshot(snapshot)
                Expr.Identifier(id.span, id)
            }

            is GenericsResult.Failure -> Expr.Identifier(id.span, id)
        }
    }

    sealed class GenericsResult {
        data object NotGeneric : GenericsResult()
        data object Failure : GenericsResult()
        class Success(
            val openAngle: Span,
            val closeAngle: Span,
            val generics: Seq<Expr.Identifier>,
        ) : GenericsResult()
    }

    fun GenericsResult.Success.checkRemaining() {
        when (closeAngle.len) {
            1 -> {}

            2 -> error(bundle.get("diag.redundant-gt")).apply {
                val redundancy = closeAngle.takeLast(1)
                label(redundancy)
                help(bundle.get("diag.redundant-gt.help"))
                    .insert(redundancy, " ")
            }

            3 -> error(bundle.get("diag.redundant-sar")).apply {
                val redundancy = closeAngle.takeLast(2)
                label(redundancy)
                help(bundle.get("diag.redundant-sar.help"))
                    .insert(redundancy, " ")
            }
        }
    }

    fun GenericsResult.Success.checkEmpty() {
        if (generics.isEmpty) {
            error(bundle.get("diag.empty-generics"))
                .label(between(openAngle, closeAngle.takeFirst(1)))
        }
    }

    /**
     * 在`check(TokenType.LESS)`之后调用
     * @return [GenericsResult.NotGeneric] 可能不是泛型，建议回溯；
     * [GenericsResult.Failure] 无泛型或解析失败，无需回溯
     */
    private fun generics(): GenericsResult {
        val start = next().span // <
        val gens = Seq<Expr.Identifier>(2)
        while (true) {
            // 文件结束，建议回溯
            if (isAtEnd) {
                return GenericsResult.Failure
            }
            // 正常结束并返回剩余的结束符
            if (check(TokenType.CLOSE_ANGLES)) {
                return GenericsResult.Success(start, next().span, gens)
            }
            if (check(TokenType.IDENTIFIER)) {
                val id = next()

                // 检查子泛型
                if (check(TokenType.LESS)) {
                    when (val subGenerics = generics()) {
                        is GenericsResult.NotGeneric, is GenericsResult.Failure -> {
                            // 子泛型解析失败，本泛型同样失败
                            return subGenerics
                        }

                        is GenericsResult.Success -> {
                            subGenerics.checkEmpty()
                            gens.add(
                                Expr.Identifier(
                                    between(id, subGenerics.closeAngle.takeFirst(1)),
                                    id,
                                    subGenerics.generics
                                )
                            )
                            when (subGenerics.closeAngle.len) {
                                // `>`属于子泛型
                                1 -> {}
                                // `>>`，第一个`>`属于子泛型
                                2 -> {
                                    return GenericsResult.Success(
                                        start,
                                        subGenerics.closeAngle.takeLast(1),
                                        gens
                                    )
                                }
                                // `>>>`，第一个`>`属于子泛型
                                3 -> {
                                    return GenericsResult.Success(
                                        start,
                                        subGenerics.closeAngle.takeLast(2),
                                        gens
                                    )
                                }
                            }
                        }
                    }
                } else {
                    gens.add(Expr.Identifier(id))
                }
                match(TokenType.COMMA)
                continue
            }
            val parentId = prevToken
            val afterParentId = lookAhead(0)
            // 能恢复到结束符则报错并返回零个泛型，无需回溯
            if (TokenType.CLOSE_ANGLES.contains(
                    recover(TokenType.RECOVERY + TokenType.CLOSE_ANGLES - TokenType.RPAREN)
                )
            ) {
                error(bundle.get("diag.miss-ident-as-type-param"))
                    .label(between(parentId, prevToken))
                return GenericsResult.Failure
            }
            // 失败后选择不回溯则保留的错误诊断
            error(bundle.get("diag.miss-type-param-end")).apply {
                label(between(afterParentId, prevToken))
                help(bundle.get("diag.miss-type-param-end.help"))
                    .insert(afterParentId, ">")
            }
            // 失败，建议回溯
            return GenericsResult.NotGeneric
        }
    }

    /**
     * 传入[TokenType.IDENTIFIER]后尝试解析类型注解，
     * 失败则返回[subject]
     * @param subject 已被消耗的[TokenType.IDENTIFIER]/[TokenType.LITERALS]
     */
    private fun annotation(subject: Expr.Identifier): Expr {
        // id :
        if (!isStmtEnd && check(TokenType.COLON)) {
            val colon = next()
            if (!subject.generics.isEmpty) {
                error(bundle.get("diag.unexpected-generic-type-in-type-annotation"))
                    .label(subject.span.takeLast(subject.span.len - subject.token.span.len))
            }
            val enums = anonymousEnum() ?: return Expr.Dummy(between(colon, prevToken))
            if (!enums.isEmpty) return Expr.TypeAnnotation(subject, enums)
            // 如果标注数量为0，视作Identifier
        }
        return subject
    }

    private fun anonymousEnum(): Seq<Expr>? {
        val enums = Seq<Expr>(3)
        // ?
        if (check(TokenType.QUESTION_MARK)) {
            enums.add(Expr.Identifier(Token(next().span, TokenType.IDENTIFIER, "Null")))
            if (check(TokenType.OR)) {
                warning(bundle.get("diag.redundant-or"))
                    .label(next(), "")
            }
        }

        // tuple1 | tuple2 ...
        var hasError = false
        while (!isAtEnd) {
            if (check(TokenType.LPAREN)) {
                val tuple = tuple()
                if (tuple is Expr.Dummy) {
                    hasError = true
                } else if (!hasError) {
                    enums.add(tuple)
                }
            } else {
                val id = identifier()
                if (id is Expr.Dummy) {
                    hasError = true
                } else if (!hasError) {
                    enums.add(id)
                }
            }
            if (!match(TokenType.OR)) break
        }
        if (hasError) return null
        return enums
    }

    /**
     * 元组，先`check(TokenType.LPAREN)`再调用
     */
    private fun tuple(): Expr {
        val lParen = next()
        val scope = openScope(ScopeKind.TUPLE, lParen)
        var hasError = false
        val elements = seq(
            { identifier() },
            EnumSet.of(TokenType.COMMA, TokenType.NEWLINE),
            { true },
            {
                hasError = true
                error(bundle.get("diag.miss-tuple-separator"))
                    .label(lookAhead(0))
                    .label(lParen, bundle.get("diag.tuple-start"))
            },
            TokenType.RPAREN,
            true,
            scope = scope,
        )
//         TODO style提示
        if (hasError || scope.aborted) return Expr.Dummy(between(lParen, prevToken))
        return Expr.Tuple(between(lParen, prevToken), elements)
    }

    /**
     * 解析一组连续表达式序列
     * @param elementProv 解析并返回一个序列元素，无法解析时请返回[Expr.Dummy]/`null`以调用恢复，务必消耗元素，否则死循环
     * @param separators 分隔符，方法内已有自带的分隔符[matchStmtEnd]，可填`setOf()`以作空格
     * @param isSeparatorOptional 是否允许省略分隔符
     * @param missSeparator 当`expect(end)`失败时使用missSeparator报错
     * @param end 序列结束标志
     * @param consumeEnd 是否消耗[end]，`false`时不强求[end]出现
     * @param missEnd 错误恢复到EOF时调用，[consumeEnd]为`false`时不使用
     * @param scope 本序列所属的定界符作用域；给出时由作用域栈统一负责闭合、缺括号报错与外层闭合符冒泡
     */
    private fun seq(
        elementProv: Prov<Expr?>,
        separators: EnumSet<TokenType>,
        isSeparatorOptional: Boolp,
        missSeparator: () -> Unit,
        end: TokenType,
        consumeEnd: Boolean,
        missEnd: () -> Unit = {},
        scope: Scope? = null,
    ): Seq<Expr> {
        val seq = Seq<Expr>()
        while (true) {
            // 到达序列结尾：闭合并按需消耗 end
            if (scope != null) {
                if (closeIfMatched(scope)) {
                    if (consumeEnd) next()
                    return seq
                }
            } else if (check(end)) {
                if (consumeEnd) next()
                return seq
            }
            // 遇到外层闭合符：本层缺少结束符，报错并把控制权交还外层
            if (scope != null && abortOnForeignCloser(scope)) return seq
            // 遇到语句起始 token：本层不缺闭合符却停下，说明此处已不属于本序列
            if (isStatementStart(lookAhead(0).type)) {
                if (scope != null) reportMissingCloser(scope, lookAhead(0))
                return seq
            }
            if (isAtEnd) {
                if (scope != null) reportMissingCloser(scope, lookAhead(0))
                else if (consumeEnd) missEnd()
                return seq
            }

            val snapshot = createSnapshot()
            val element = elementProv.get()
            if (element == null || element is Expr.Dummy) {
                restoreSnapshot(snapshot)
            } else {
                // 成功解析元素
                seq.add(element)
                // 有分隔符则消耗并继续解析
                if (separators.contains(lookAhead(0).type)) {
                    next()
                    continue
                }
                // 允许忽略分隔符且不是错误分隔符则继续解析
                if (isSeparatorOptional.get() && separators.isNotEmpty()) continue
                // 没有检查到正确分隔符，应该有结束符
                if (scope != null) {
                    if (closeIfMatched(scope)) {
                        if (consumeEnd) next()
                        return seq
                    }
                } else if (check(end)) {
                    if (consumeEnd) next()
                    return seq
                }
                // 没有结束符，可能是漏掉了分隔符，报错并恢复
                if (!isAtEnd) missSeparator()
            }
            // 解析失败，开始恢复
            when (val type = recoverByTokenTree(separators + end)) {
                end -> {
                    if (scope != null && !closeIfMatched(scope)) {
                        reportMissingCloser(scope, lookAhead(0))
                    }
                    if (consumeEnd) next()
                    return seq
                }

                TokenType.EOF -> {
                    if (scope != null) reportMissingCloser(scope, lookAhead(0))
                    else if (consumeEnd) missEnd()
                    return seq
                }

                else -> {
                    if (separators.contains(type)) {
                        next()
                        continue
                    }
                    // 外层闭合符（或其它同步点）：本层提前结束，控制权交还调用方
                    if (scope != null && !scope.aborted) reportMissingCloser(scope, lookAhead(0))
                    return seq
                }
            }
        }
    }

    // ========== 工具方法 ==========
    // ---------- Token基础方法 ----------

    /**
     * 向前推进一个token
     */
    private fun next(): Token {
        return input.next()
    }

    /**
     * 向前推进几个token
     * @param step must be in [1,LookAheadWindow.capacity]
     */
    private fun next(step: Int): Token {
        return input.next(step)
    }

    /**
     * 前瞻token
     * @param index 0表示当前token，1表示下一个token，2表示下下个token，依次类推
     */
    private fun lookAhead(index: Int): Token =
        input.lookAhead(index)

    /**
     * 检查是否为文件结尾
     * @return 下一个Token为`EOF`时返回true，自动消耗NEWLINE
     */
    private val isAtEnd: Boolean
        get() {
            val nextType = lookAhead(0).type
            if (nextType == TokenType.NEWLINE) {
                // 第二个不会是NEWLINE，由Lexer.scanToken()保证
                if (lookAhead(1).type == TokenType.EOF) {
                    next() // 跳过NEWLINE
                    return true
                }
                return false
            }
            return nextType == TokenType.EOF
        }

    /**
     * 检查是否为语句结尾
     * @return 下一个Token为`NEWLINE`或`SEMICOLON`或`EOF`时返回true
     */
    private val isStmtEnd: Boolean
        get() {
            val peekType = lookAhead(0).type
            return peekType in TokenType.STMT_END
        }

    /**
     * 不支持NEWLINE
     */
    private fun check(type: TokenType): Boolean {
        val nextType = lookAhead(0).type
        if (nextType == TokenType.NEWLINE) {
            // 第二个不会是NEWLINE，由Lexer.scanToken()保证
            if (lookAhead(1).type == type) {
                next() // 跳过NEWLINE
                return true
            }
            return false
        }
        return nextType == type
    }

    /**
     * 下一个Token的类型在参数中，则返回true
     * 不支持NEWLINE
     */
    private fun check(types: Set<TokenType>): Boolean {
        val nextType = lookAhead(0).type
        if (nextType == TokenType.NEWLINE) {
            // 第二个不会是NEWLINE，由Lexer.scanToken()保证
            if (types.contains(lookAhead(1).type)) {
                next() // 跳过NEWLINE
                return true
            }
            return false
        }
        return types.contains(nextType)
    }

    /**
     * 下一个Token.literal等于参数，则返回true
     * 不支持NEWLINE
     */
    private fun check(text: String): Boolean {
        val next = lookAhead(0)
        if (next.type == TokenType.NEWLINE) {
            // 第二个不会是NEWLINE，由Lexer.scanToken()保证
            if (text == lookAhead(1).literal) {
                next() // 跳过NEWLINE
                return true
            }
            return false
        }
        return text == next.literal
    }

    /**
     * 下一个Token类型是参数之一时，则返回true
     * 不支持NEWLINE
     * 如果可以请使用[check]<Set>，因为它更快
     */
    private fun check(vararg types: TokenType): Boolean {
        val nextType0 = lookAhead(0).type
        if (nextType0 == TokenType.NEWLINE) {
            // 第二个不会是NEWLINE，由Lexer.scanToken()保证
            val nextType1 = lookAhead(1).type
            for (expected in types) {
                if (nextType1 == expected) {
                    next() // 跳过NEWLINE
                    return true
                }
            }
            return false
        }
        for (expected in types) {
            if (nextType0 == expected) return true
        }
        return false
    }

    private fun match(type: TokenType): Boolean {
        if (check(type)) {
            next()
            return true
        }
        return false
    }

    private fun match(types: Set<TokenType>): Boolean {
        if (check(types)) {
            next()
            return true
        }
        return false
    }

    private fun match(text: String): Boolean {
        if (check(text)) {
            next()
            return true
        }
        return false
    }

    /**
     * 下一个Token类型是参数之一时，则返回true并推进
     * 不支持NEWLINE
     * 如果可以请使用[match]<Set>，因为它更快
     */
    private fun match(vararg texts: String): Boolean {
        for (text in texts) {
            if (check(text)) {
                next()
                return true
            }
        }
        return false
    }

    // ---------- Token高级方法 ----------

    /**
     * 若下一个token不是指定类型的则返回null并报告错误，否则返回该token。
     */
    private fun expect(type: TokenType): Token? {
        if (check(type)) return lookAhead(0)

        error(bundle.format("diag.miss-token", type))
            .label(lookAhead(0), type.toString())
        return null
    }

    /**
     * 若下一个token不是指定类型的则返回null并报告错误并将错误输入Cons，否则返回该token。
     */
    private fun expect(type: TokenType, cons: Cons<Diagnostic>): Token? {
        if (check(type)) return lookAhead(0)

        cons.get(
            error(bundle.format("diag.miss-token", type))
                .label(lookAhead(0), type.toString()),
        )
        return null
    }

    /**
     * 检查 ; \n EOF 作为语句结束符，若有则推进；
     * 检查 { } 作为语句结束符，不推进；
     * 不报错。
     */
    private fun matchStmtEnd(): Boolean {
        val type = lookAhead(0).type
        if (type in TokenType.STMT_END) {
            next()
            return true
        }
        if (type in TokenType.BRACES) return true
        return false
    }

    /**
     * 检查 ; \n EOF 作为语句结束符，推进；
     * 检查 { } 作为语句结束符，不推进；
     * 都没有则报错。
     *
     * @param stmt 需要匹配结束符的语句
     */
    private fun consumeStmtEnd(stmt: Stmt? = null): Boolean {
        if (matchStmtEnd()) return true
        // 闭合符不是语句结束符：交给错误恢复/外层作用域处理，不额外报「缺换行」
        if (isCloser(lookAhead(0).type)) return false
        // 如果没有找到，报告错误
        error(bundle.get("diag.miss-stmt-end")).apply {
            label(lookAhead(0), "")
            stmt?.let {
                this.label(stmt, bundle.get("diag.miss-stmt-end.stmt"))
            }
        }
        return false
    }

    /**
     * 若下一个token不是指定类型的则报告错误并返回null，否则返回该token并推进
     */
    private fun consume(type: TokenType): Token? {
        if (check(type)) return next()

        error(bundle.format("diag.miss-token", type))
            .label(lookAhead(0), type.toString())
        return null
    }

    /**
     * 若下一个token不是指定类型的则报告错误并返回null，否则返回该token并推进
     */
    private fun consume(type: TokenType, action: () -> Unit): Token? {
        if (check(type)) return next()
        action()
        return null
    }

    // ---------- 定界符作用域 ----------

    /**
     * 开启一个定界符作用域并入栈，供 `(` / `[` / `{` 的解析函数在消耗 opener 后调用。
     * @param opener 已消耗的开符 token，用于报错定位
     */
    private fun openScope(kind: ScopeKind, opener: Token): Scope {
        val scope = Scope(opener, kind)
        scopes.add(scope)
        return scope
    }

    /** 弹出 [scope] 及其之上的所有帧（正常闭合时其上应无帧） */
    private fun popScope(scope: Scope) {
        for (index in scopes.size - 1 downTo 0) {
            if (scopes.get(index) === scope) {
                scopes.truncate(index)
                return
            }
        }
    }

    /**
     * 尝试用当前 token 闭合 [scope]。
     * @return 命中时弹出本层并返回 true（**不消耗** token，由调用方推进）
     */
    private fun closeIfMatched(scope: Scope): Boolean {
        if (!check(scope.closer)) return false
        popScope(scope)
        return true
    }

    /**
     * 要求当前 token 必须是 [scope] 的闭合符：
     * 成功时弹出本层并返回 true；失败时报告一条诊断（每层只报一次）并返回 false。
     */
    private fun expectClose(scope: Scope): Boolean {
        if (closeIfMatched(scope)) return true
        reportMissingCloser(scope, lookAhead(0))
        return false
    }

    /**
     * 当前 token 是「其它作用域的闭合符」时，说明本层缺少闭合符：
     * 报告、弹出本层并返回 true（调用方应立即返回部分节点，把控制权交还外层）。
     *
     * 三种处置：
     * - 该闭合符有配对开符（属于外层）→ 按「插入」报错，让外层照常闭合；
     * - 没有配对开符，且本层是表达式/列表层 → 更像把闭合符写错了（`print("a"}`），
     *   按「替换」报错并把它当成本层闭合符消费；
     * - 其余无主闭合符（语句/声明体里的 stray `)`）→ 报「多余」并丢弃，本层继续解析。
     */
    private fun abortOnForeignCloser(scope: Scope): Boolean {
        val token = lookAhead(0)
        val type = token.type
        if (!TokenType.CLOSERS.contains(type) || type == scope.closer) return false
        if (findScopeByCloser(type) == null) {
            if (scope.kind.replacesUnmatchedCloser) {
                reportReplacedCloser(scope, token)
                next()
                return true
            }
            reportUnmatchedCloser(token)
            next()
            return false
        }
        reportMissingCloser(scope, token)
        return true
    }

    /** 在作用域栈里自上而下找第一个以 [closer] 闭合的帧 */
    private fun findScopeByCloser(closer: TokenType): Scope? {
        for (index in scopes.size - 1 downTo 0) {
            val scope = scopes.get(index)
            if (scope.closer == closer) return scope
        }
        return null
    }

    /**
     * 报告 [scope] 未闭合（每层只报一次），并把它从栈里弹出。
     *
     * 三种形态：到 EOF（`diag.unclosed-delimiter`）、遇到别的闭合符（`diag.mismatched-delimiter`）、
     * 遇到普通 token 却要求闭合（`diag.miss-closer`）。
     */
    private fun reportMissingCloser(scope: Scope, found: Token) {
        scope.aborted = true
        popScope(scope)
        if (scope.reported) return
        scope.reported = true

        val openerLabel = bundle.format("diag.opened-here", scope.opener.type, scope.kind.description())
        if (found.type == TokenType.EOF) {
            error(bundle.format("diag.unclosed-delimiter", scope.closer))
                .label(scope.opener, openerLabel)
                .help(bundle.format("diag.unclosed-delimiter.help", scope.closer))
                .insert(found, closerLiteral(scope.closer))
            return
        }
        if (TokenType.CLOSERS.contains(found.type)) {
            val diagnostic = error(bundle.format("diag.mismatched-delimiter", scope.closer, found.type))
                .label(found, bundle.format("diag.expected-here", scope.closer))
                .label(scope.opener, openerLabel)
            diagnostic.help(bundle.format("diag.insert-closer.help", scope.closer, found.type))
                .insert(found, closerLiteral(scope.closer))
            // 这个闭合符属于更外层：顺便指出它是关哪一层的，方便对照修复
            findScopeByCloser(found.type)?.let { outer ->
                diagnostic.note(bundle.format("diag.outer-closer.note", found.type, outer.kind.description()))
                    .label(outer.opener, "")
            }
            return
        }

        // 普通 token：本层缺闭合符，但代码还在继续
        error(bundle.format("diag.miss-closer", scope.closer, scope.kind.description()))
            .label(found, bundle.format("diag.expected-here", scope.closer))
            .label(scope.opener, openerLabel)
            .help(bundle.format("diag.miss-closer.help", scope.closer))
            .insert(found, closerLiteral(scope.closer))
    }

    /**
     * 无配对开符、出现在表达式/列表层时：把 [found] 当作本层闭合符的笔误，
     * 报「不匹配」并建议用 [Scope.closer] 替换它（调用方随后会消费该 token）。
     */
    private fun reportReplacedCloser(scope: Scope, found: Token) {
        scope.aborted = true
        popScope(scope)
        if (scope.reported) return
        scope.reported = true

        error(bundle.format("diag.mismatched-delimiter", scope.closer, found.type))
            .label(found, bundle.format("diag.expected-here", scope.closer))
            .label(
                scope.opener,
                bundle.format("diag.opened-here", scope.opener.type, scope.kind.description()),
            )
            .help(bundle.format("diag.replace-closer.help", found.type, scope.closer))
            .replace(found, closerLiteral(scope.closer))
    }

    /** 报告一个没有任何开符与之配对的闭合符，并给出删除建议 */
    private fun reportUnmatchedCloser(token: Token) {
        error(bundle.format("diag.unmatched-closer", token.type))
            .label(token, "")
            .help(bundle.get("diag.unmatched-closer.help"))
            .delete(token)
    }

    /** `)` / `]` / `}` 的源码字面量（`TokenType.toString()` 带反引号，不适合作为插入建议） */
    private fun closerLiteral(type: TokenType): String = when (type) {
        TokenType.RPAREN -> ")"
        TokenType.RBRACKET -> "]"
        TokenType.RBRACE -> "}"
        else -> type.toString()
    }

    private fun isCloser(type: TokenType): Boolean = TokenType.CLOSERS.contains(type)

    /** 语句起始 token（用于判断「当前位置不可能再是表达式/列表元素，本层缺闭合符」） */
    private fun isStatementStart(type: TokenType): Boolean = when (type) {
        TokenType.USE, TokenType.SET, TokenType.MACRO, TokenType.CONST,
        TokenType.IF, TokenType.FOR, TokenType.WHILE,
        TokenType.BREAK, TokenType.CONTINUE, TokenType.STRUCT,
        TokenType.MATCH, TokenType.FN, TokenType.RETURN, TokenType.ENUM,
        -> true

        else -> false
    }

    /**
     * 列表/表达式内部换行后直接跟着闭合符或语句起始 token 时，换行不属于本层内容，先消费它。
     * @return 换行后是语句起始 token（本层缺闭合符，调用方应报错并返回）
     */
    private fun skipNewlineBeforeBoundary(): Boolean {
        if (lookAhead(0).type != TokenType.NEWLINE) return false
        val nextType = lookAhead(1).type
        if (!isCloser(nextType) && !isStatementStart(nextType)) return false
        next()
        return isStatementStart(nextType)
    }

    // ---------- 错误恢复方法 ----------

    /**
     * 错误恢复：向前扫描直到出现 [expected] 中的 token，或某个已知作用域的闭合符。
     * 扫描期间不消耗停下的 token。
     *
     * 与旧实现（临时局部括号栈）的区别：用真实的 [scopes] 判断闭合符归属：
     * - 命中 [expected] → 返回，交调用方处理；
     * - 遇到某个作用域的闭合符 → 返回该类型，由调用方决定「闭合本层」还是「向外层冒泡」；
     * - 无主闭合符 → 报告并丢弃；
     * - 到 EOF → 返回 EOF，交调用方报告未闭合（每层各报一次）。
     */
    private fun recoverByTokenTree(expected: Set<TokenType>): TokenType {
        while (true) {
            val token = lookAhead(0)
            val type = token.type
            if (type == TokenType.EOF) return TokenType.EOF
            // 闭合符优先按作用域栈处理：无主的直接报错并丢弃，避免调用方静默吞掉
            if (TokenType.CLOSERS.contains(type)) {
                if (findScopeByCloser(type) == null) {
                    reportUnmatchedCloser(token)
                    next()
                    continue
                }
                return type
            }
            if (expected.contains(type)) return type
            next()
        }
    }

    /**
     * 错误恢复，扫描直到期望的TokenType，但不会消耗
     */
    private fun recover(expecteds: Set<TokenType>): TokenType {
        while (!isAtEnd) {
            val type = lookAhead(0).type
            if (expecteds.contains(type)) return type
            next()
        }
        return TokenType.EOF
    }

    private fun createSnapshot(): Snapshot {
        return Snapshot(
            input.deepCopy(),
            lexer.createSnapshot(),
            scopeDepth = scopes.size,
        )
    }

    private fun createSnapshotWithDiagHandler(): Snapshot {
        return Snapshot(
            input.deepCopy(),
            lexer.createSnapshot(),
            diagsSnapshot = context.diagHandler.createSnapshot(),
            scopeDepth = scopes.size,
        )
    }

    private fun restoreSnapshot(snapshot: Snapshot) {
        input = snapshot.inputSnapshot
        lexer.restoreSnapshot(snapshot.lexerSnapshot)
        snapshot.diagsSnapshot?.let { context.diagHandler.restoreSnapshot(it) }
        // 投机解析只会新增/闭合自己的帧：把回溯点之后压入的帧截掉即可（O(1)）
        if (scopes.size > snapshot.scopeDepth) scopes.truncate(snapshot.scopeDepth)
    }

    // ---------- 类生成方法 ----------
    private fun token(type: TokenType, from: Token): Token {
        return Token(from.span, type)
    }

    /**
     * 生成当前文件的span
     * @param from 起始
     * @param to 末尾
     * @param dropTail 删减末尾长度（用于[Parser.generics]构造时删减不属于本层泛型的`>`)
     */
    private fun between(from: Spanned, to: Spanned, dropTail: Int = 0): Span {
        return Span.between(sourceFile.index, from.span().start, to.span().end - dropTail)
    }

    /** 将复合赋值运算符token拆分 */
    private fun subOperatorOf(token: Token): Token {
        val subType = when (token.type) {
            TokenType.PLUS_ASSIGN -> TokenType.PLUS
            TokenType.MINUS_ASSIGN -> TokenType.MINUS
            TokenType.STAR_ASSIGN -> TokenType.STAR
            TokenType.SLASH_ASSIGN -> TokenType.SLASH
            TokenType.STAR_STAR_ASSIGN -> TokenType.STAR_STAR
            TokenType.PERCENT_ASSIGN -> TokenType.PERCENT
            TokenType.PERCENT_PERCENT_ASSIGN -> TokenType.PERCENT_PERCENT
            TokenType.SLASH_SLASH_ASSIGN -> TokenType.SLASH_SLASH
            TokenType.AND_ASSIGN -> TokenType.AND
            TokenType.OR_ASSIGN -> TokenType.OR
            TokenType.CARET_ASSIGN -> TokenType.CARET
            TokenType.SHL_ASSIGN -> TokenType.SHL
            TokenType.SAR_ASSIGN -> TokenType.SAR
            TokenType.SHR_ASSIGN -> TokenType.SHR
            else -> kotlin.error("Unreachable")
        }
        return Token(token.span, subType)
    }

    private fun error(text: String): ParserDiag {
        val e = ParserDiag(text, Diagnostic.DiagLevel.ERROR)
        context.diagHandler.addError(e)
        return e
    }

    private fun warning(text: String): ParserDiag {
        val w = ParserDiag(text, Diagnostic.DiagLevel.WARNING)
        context.diagHandler.addWarning(w)
        return w
    }

    // ========== 工具类 ==========
    /**
     * 惰性 Token 窗口：内部持有 [Queue] 作为前瞻缓冲，
     * 按需实时调用 [Lexer.scanToken] 扫描下一个 Token，绝不预生成全部 Token。
     */
    private inner class InputWindow {
        private val capacity: Int = 5
        private val buffer = Queue<Token>(capacity)
        var prevToken: Token = Token(Span(sourceFile.index, 0, 0), TokenType.UNKNOWN)

        /**
         * 返回当下的Token并推进一步
         */
        fun next(): Token {
            if (buffer.size < 1) {
                prevToken = lexer.scanToken()
            } else {
                prevToken = buffer.removeFirst()
            }
            return prevToken
        }

        /**
         * 返回lookAhead(step)并推进到其后一个
         */
        fun next(step: Int): Token {
            if (step == 0) return next()
            repeat(step - 1) {
                next()
            }
            return next()
        }

        fun lookAhead(index: Int): Token {
            require(index in 0 until capacity) { "index must be between 0 and ${capacity - 1}" }
            repeat(index - buffer.size + 1) {
                buffer.addLast(lexer.scanToken())
            }
            return buffer.get(index)
        }

        fun deepCopy(): InputWindow {
            return InputWindow().also {
                this.buffer.forEach { t -> it.buffer.addLast(t) }
                it.prevToken = this.prevToken
            }
        }
    }

    /**
     * 一个尚未闭合的定界符作用域；只服务报错定位与错误恢复，不参与 AST 构造。
     * @param opener 开符 token（用于 label）
     */
    private class Scope(
        val opener: Token,
        /** 作用域种类；元组在解析过程中可由「分组表达式」改判，故为可变 */
        var kind: ScopeKind,
    ) {
        val closer: TokenType get() = kind.closer

        /** 已就本层缺失闭合符报过错，避免同一层反复报 */
        var reported: Boolean = false

        /** 本层因缺失闭合符而提前结束（调用方应立即返回部分节点） */
        var aborted: Boolean = false
    }

    /**
     * 定界符作用域种类：决定期望的闭合符与报错文案（`diag.scope.*`）。
     *
     * @param replacesUnmatchedCloser 无配对开符的闭合符是否应按「写错闭合符」替换处理。
     * 表达式/列表层（`(`、`[`、数组 `{`）为 true；语句/声明体（block、match、struct…）为 false，
     * 因为用 `)` / `]` 去闭合一个语句块多半只是游离字符，删掉比替换更安全。
     */
    private enum class ScopeKind(
        val closer: TokenType,
        val descriptionKey: String,
        val replacesUnmatchedCloser: Boolean = false,
    ) {
        BLOCK(TokenType.RBRACE, "diag.scope.block"),
        MATCH_BODY(TokenType.RBRACE, "diag.scope.match-body"),
        STRUCT_BODY(TokenType.RBRACE, "diag.scope.struct-body"),
        ENUM_BODY(TokenType.RBRACE, "diag.scope.enum-body"),
        VARIANT_BODY(TokenType.RBRACE, "diag.scope.variant-body"),
        USE_LIST(TokenType.RBRACE, "diag.scope.use-list"),
        ARRAY(TokenType.RBRACE, "diag.scope.array", true),
        VARIANT_FIELDS(TokenType.RPAREN, "diag.scope.variant-fields", true),
        FUNC_PARAMS(TokenType.RPAREN, "diag.scope.func-params", true),
        CALL_ARGS(TokenType.RPAREN, "diag.scope.call-args", true),
        PATTERN_ARGS(TokenType.RPAREN, "diag.scope.pattern-args", true),
        TUPLE(TokenType.RPAREN, "diag.scope.tuple", true),
        GROUPING(TokenType.RPAREN, "diag.scope.grouping", true),
        INDEX(TokenType.RBRACKET, "diag.scope.index", true),
        ;

        fun description(): String = bundle.get(descriptionKey)
    }

    private data class Snapshot(
        val inputSnapshot: InputWindow,
        val lexerSnapshot: Lexer.LexerSnapshot,
        /** null表示不进行快照，恢复时不恢复diags */
        val diagsSnapshot: DiagHandler.DiagsSnapshot? = null,
        /** 快照时的定界符作用域栈深度，回溯时截断到该深度 */
        val scopeDepth: Int = 0,
    )
}
