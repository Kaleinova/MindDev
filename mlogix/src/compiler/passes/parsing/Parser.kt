package mlogix.compiler.passes.parsing

import arc.func.Boolp
import arc.func.Cons
import arc.func.Prov
import arc.struct.Queue
import arc.struct.Seq
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Expr.ErrorExpr
import mlogix.compiler.ast.Expr.Get
import mlogix.compiler.ast.Stmt
import mlogix.compiler.ast.Stmt.*
import mlogix.compiler.core.SourceMapManager.SourceMap
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.span.Spanned
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.diagnostic.Diagnostic.ParserDiag
import mlogix.compiler.diagnostic.DiagCollector
import java.util.*

/**
 * 一个项目 一次构造
 */
class Parser(
    private val lexer: Lexer,
    private val problems: DiagCollector,
) {
    private lateinit var sourceMap: SourceMap
    private lateinit var input: InputWindow
    private val prevToken: Token get() = input.prevToken

    /**
     * 一个文件 一次调用
     * 联动重置lexer，之后由 InputWindow 按需实时调用 lexer.scanToken() 扫描 Token
     */
    fun parse(sourceMap: SourceMap): Stmt {
        this.sourceMap = sourceMap
        lexer.reset(sourceMap)
        input = InputWindow()
        return program()
    }

    /**
     * 解析独立文本语法
     * 运行前会重置problemCollector
     */
    fun parse(source: String): Stmt {
        problems.clear()

        sourceMap = SourceMap(source)
        lexer.reset(sourceMap)
        input = InputWindow()
        return program()
    }

    // ========== 主要解析部分 ==========
    // ---------- Stmt ----------
    private fun program(): Stmt {
        val stmts = Seq<Stmt>()

        while (!isAtEnd) {
            val stmt = statement()
            if (stmt != null) {
                stmts.add(stmt)
            }
        }

        return Program(Span(0, sourceMap.length()), stmts)
    }

    private fun statement(): Stmt? = when {
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

        else -> {
            loopStmtWithFlag() ?: exprStmt()
        }
    }

    private fun useStmt(): Stmt? {
        val start = next() // consume 'use'
        val item = useItem() ?: return null
        if (!consumeStmtEnd()) recoverByTokenTree(TokenType.RECOVERY)
        return UseStmt(between(start, item), item)
    }

    private fun useItem(): UseStmt.UseItem? {
        val path = Seq<Expr.Identifier>()
        while (true) {
            when {
                check(TokenType.IDENTIFIER) -> {
                    val id = next()
                    path.add(Expr.Identifier(id))
                    if (!match(TokenType.DOT)) {
                        return UseStmt.Single(between(path[0], prevToken), path)
                    }
                    if (isStmtEnd) break
                    continue
                }

                check(TokenType.STAR) -> {
                    return UseStmt.All(if (path.isEmpty) next().span else between(path[0], next()), path)
                }

                check(TokenType.STAR_STAR) -> {
                    return UseStmt.Recursion(if (path.isEmpty) next().span else between(path[0], next()), path)
                }

                check(TokenType.LBRACE) -> {
                    val lbrace = next()

                    val items = Seq<UseStmt.UseItem>()
                    while (!match(TokenType.RBRACE)) {
                        val item = useItem()
                        if (item == null) {
                            break
                        } else {
                            items.add(item)
                        }
                        match(TokenType.COMMA)
                    }
                    return UseStmt.Multi(between((if (path.isEmpty) lbrace else path[0]), prevToken), path, items)
                }

                else -> break
            }
        }
        error("期望标识符、* 或 **")
            .point(lookAhead(0), "")
        recoverByTokenTree(TokenType.RECOVERY)
        return null
    }

    private fun block(): Stmt {
        val lBrace = next()

        val stmts = Seq<Stmt>()
        while (!check(TokenType.RBRACE)) {
            if (isAtEnd) {
                error("期望`块`语句的`}`")
                    .info(lBrace, "开头")
                    .point(lookAhead(0), "当前")
                return BlockStmt(between(lBrace, prevToken), stmts)
            }
            statement()?.let { stmts.add(it) }
        }
        val rbrace = next()

        return BlockStmt(between(lBrace, rbrace), stmts)
    }

    private fun ifStmt(): Stmt {
        val start = next()

        val condition = expression()

        if (expect(TokenType.LBRACE) == null) {
            return IfStmt(between(start, condition), condition, null, null)
        }
        val thenBranch = block()

        var elseBranch: Stmt? = null
        if (check(TokenType.ELIF)) {
            elseBranch = ifStmt()
        } else if (match(TokenType.ELSE)) {
            if (expect(TokenType.LBRACE) == null) {
                return IfStmt(between(start, condition), condition, thenBranch, null)
            }
            elseBranch = block()
        }

        return IfStmt(between(start, prevToken), condition, thenBranch, elseBranch)
    }

    private fun matchStmt(): Stmt {
        val start = next()

        val scrutinee = expression()

        if (consume(TokenType.LBRACE) == null) {
            error("期望`match`语句的`{`")
                .info(start, "语句开头")
                .point(lookAhead(0), "当前")
            return MatchStmt(between(start, scrutinee), scrutinee, null)
        }

        val branches = Seq<MatchStmt.MatchBranch>()
        while (!match(TokenType.RBRACE)) {
            if (isAtEnd) {
                error("期望`match`语句的`}`")
                    .info(start, "语句开头")
                    .point(lookAhead(0), "当前")
                return MatchStmt(
                    between(start, (if (branches.isEmpty) start else branches[branches.size - 1])),
                    scrutinee,
                    branches,
                )
            }
            val pattern = expression()
            if (consume(TokenType.ARROW) == null) {
                when (recoverByTokenTree(EnumSet.of(TokenType.NEWLINE, TokenType.RBRACE))) {
                    TokenType.EOF -> return MatchStmt(
                        between(start, (if (branches.isEmpty) start else branches[branches.size - 1])),
                        scrutinee,
                        branches,
                    )

                    else -> continue
                }
            }
            val body = statement()
            branches.add(MatchStmt.MatchBranch(between(pattern, prevToken), pattern, body))
        }
        return MatchStmt(
            between(start, prevToken),
            scrutinee,
            branches,
        )
    }

    private fun forStmt(flag: Expr.Identifier?): Stmt {
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
        if (expect(TokenType.LBRACE) { e: Diagnostic -> e.info(head, "`for`语句") } == null) {
            return ForStmt(between(start, prevToken), flag, varDecl, expr, null)
        }
        if (expect(TokenType.LBRACE) { e: Diagnostic -> e.info(head, "`for`语句") } == null) {
            when (recoverByTokenTree(EnumSet.of(TokenType.RBRACE))) {
                TokenType.RBRACE -> error("不匹配的闭定界符").point(next(), "")
                TokenType.EOF -> Unit
                else -> kotlin.error("Unreachable")
            }
            return ForStmt(between(start, expr ?: varDecl!!), flag, varDecl, expr, null)
        }
        val body = block()

        return ForStmt(between(start, body), flag, varDecl, expr, body)

    }

    private fun whileStmt(flag: Expr.Identifier?): Stmt {
        val head = next()
        val start = flag ?: head

        val expr = expression()

        if (expect(TokenType.LBRACE) { e: Diagnostic -> e.info(head, "`while`语句") } == null) {
            when (recoverByTokenTree(EnumSet.of(TokenType.RBRACE))) {
                TokenType.RBRACE -> error("不匹配的闭定界符").point(next(), "")
                TokenType.EOF -> Unit
                else -> kotlin.error("Unreachable")
            }
            return WhileStmt(between(start, expr), flag, expr, null)
        }
        val body = block()
        return WhileStmt(between(start, body), flag, expr, body)

    }


    private fun loopStmtWithFlag(): Stmt? {
        val snapshot = createSnapshot()
        if (check(TokenType.IDENTIFIER)) {
            val flag = next()
            if (!isStmtEnd && match(TokenType.COLON)) {
                if (check(TokenType.FOR)) return forStmt(Expr.Identifier(flag))
                if (check(TokenType.WHILE)) return whileStmt(Expr.Identifier(flag))
            }
            if (check(TokenType.COLON)) {
                // 冒号跟flag不在同一行
                error("循环标签中`:`必须与标签同行")
                    .info(flag, "标签")
                    .point(next(), "")
                if (check(TokenType.FOR)) return forStmt(Expr.Identifier(flag))
                if (check(TokenType.WHILE)) return whileStmt(Expr.Identifier(flag))
            }
        }
        restoreSnapshot(snapshot)
        return null
    }


    private fun fnStmt(): Stmt {
        val start = next()

        val name = consume(TokenType.IDENTIFIER) { e -> e.info(start, "函数声明必须有函数名") }
        val lParen: Token?
        if (name == null) {
            if (!check(TokenType.LPAREN)) {
                return FnStmt(start.span, null, null, null, null)
            }
            lParen = next()
        } else {
            lParen = consume(TokenType.LPAREN) { e -> e.info(start, "函数声明必须有形参") }
            if (lParen == null) {
                return FnStmt(between(start, name), name, null, null, null)
            }
        }

        var isSeparatorOptional = false
        val parameters = seq(
            {
                consume(TokenType.IDENTIFIER) { e ->
                    e.info(lookAhead(0), "函数形参应该是标识符")
                }?.let {
                    val expr = annotation(Expr.Identifier(it))
                    isSeparatorOptional = expr !is Expr.Annotation
                    return@let expr
                }
            },
            EnumSet.of(TokenType.COMMA, TokenType.NEWLINE),
            { isSeparatorOptional },
            { error("期望`,`或换行符作为形参分隔符").info(lParen, "形参开头") },
            TokenType.RPAREN,
            true,
            { error("期望`)`作为函数声明形参末尾").info(lParen, "形参开头") }
        )

        val results = Seq<Expr>(3)
        if (check(TokenType.ARROW)) {
            val arrow = next()
            if (check(TokenType.QUESTION_MARK)) {
                results.add(Expr.Identifier(Token(next().span, TokenType.IDENTIFIER, "Null")))
                if (check(TokenType.COMMA)) {
                    warning("多余的`,`")
                        .point(next(), "")
                }
            }

            var isSeparatorOptional = false
            results.add(
                seq(
                    {
                        consume(TokenType.IDENTIFIER) { e ->
                            e.info(lookAhead(0), "函数返回值应该是标识符")
                        }?.let {
                            val expr = annotation(Expr.Identifier(it))
                            isSeparatorOptional = expr !is Expr.Annotation
                            return@let expr
                        }
                    },
                    EnumSet.of(TokenType.COMMA, TokenType.NEWLINE),
                    { isSeparatorOptional },
                    { error("期望`,`或换行符作为返回值分隔符").info(arrow, "返回值开头") },
                    TokenType.LBRACE,
                    false,
                    { error("期望`{`作为返回值声明末尾").info(lParen, "形参开头") }
                ))
            if (results.isEmpty) {
                error("`->`之后必须声明返回值")
                    .point(arrow, "")
            }
        }

        if (check(TokenType.LBRACE)) {
            val body = block()
            return FnStmt(between(start, body), name, parameters, results, body)
        } else {
            return FnStmt(between(start, prevToken), name, parameters, results, null)
        }
    }

    private fun breakStmt(): Stmt {
        val start = next()
        if (matchStmtEnd()) return BreakStmt(start.span, null)
        val flag = consume(TokenType.IDENTIFIER) ?: return BreakStmt(start.span, null)
        if (!consumeStmtEnd()) recoverByTokenTree(TokenType.RECOVERY)
        return BreakStmt(between(start, flag), Expr.Identifier(flag))
    }

    private fun continueStmt(): Stmt {
        val start = next()
        if (matchStmtEnd()) return ContinueStmt(start.span, null)
        val flag = consume(TokenType.IDENTIFIER) ?: return ContinueStmt(start.span, null)
        if (!consumeStmtEnd()) recoverByTokenTree(TokenType.RECOVERY)
        return ContinueStmt(between(start, flag), Expr.Identifier(flag))
    }

    private fun returnStmt(): Stmt {
        val start = next()
        if (matchStmtEnd()) return ReturnStmt(start.span, null)
        val expr = expression()
        if (!consumeStmtEnd()) recoverByTokenTree(TokenType.RECOVERY)
        return ReturnStmt(between(start, expr), expr)
    }

    private fun setStmt(): Stmt? {
        val start = next()

        if (isStmtEnd) {
            error("未声明变量的`set`语句")
                .info(start, "")
                .point(lookAhead(0), "")
            return null
        }
        var expr = expression()
        if (expr is Expr.Identifier) {
            expr = annotation(expr)
        }
        val assignStmt = assignStmt(expr)
        if (assignStmt == null) {
            if (!consumeStmtEnd()) recoverByTokenTree(TokenType.RECOVERY)
            return SetVarStmt(between(start, expr), expr, null)
        } else {
            // assignStmt(_)消耗了StmtEnd
            return SetVarStmt(between(start, assignStmt), expr, assignStmt)
        }
    }

    private fun exprStmt(): Stmt? {
        val expr = expression()
        if (expr is ErrorExpr) {
            recoverByTokenTree(TokenType.RECOVERY)
            return null
        }

        val assignStmt = assignStmt(expr)

        if (assignStmt == null) {
            if (!consumeStmtEnd()) recoverByTokenTree(TokenType.RECOVERY)
            return ExprStmt(expr.span, expr)
        } else {
            // assignStmt(_)消耗了StmtEnd
            return assignStmt
        }
    }

    /**
     * 解析 赋值 `= ...` 或 复合赋值 `+= ...`
     * @param expr `=`或复合赋值运算符前的表达式
     * @return 没有`=`或者复合赋值运算符时返回null;有时返回AssignStmt并推进
     */
    private fun assignStmt(expr: Expr): Stmt? {
        if (isStmtEnd) {
            if (check(TokenType.ASSIGNS)) {
                error("赋值语句缺少左侧表达式")
                    .point(next(), "")
                recoverByTokenTree(TokenType.RECOVERY)
            }
            return null
        }
        if (check(TokenType.ASSIGN)) {
            val operator = next()
            if (isStmtEnd) {
                error("赋值语句缺少右侧表达式")
                    .point(next(), "")
                recoverByTokenTree(TokenType.RECOVERY)
                return null
            }
            val value = expression()

            if (!consumeStmtEnd()) recoverByTokenTree(TokenType.RECOVERY)
            return AssignStmt(between(expr, value), expr, operator, value)

        } else if (check(TokenType.ASSIGNS)) {
            val operator = next()
            if (isStmtEnd) {
                error("赋值语句缺少右侧表达式")
                    .point(next(), "")
                recoverByTokenTree(TokenType.RECOVERY)
                return null
            }
            val assign = Token(operator.span, TokenType.ASSIGN)
            val subOperator = subOperatorOf(operator)
            val right = expression()
            return AssignStmt(
                between(expr, right),
                expr,
                assign,
                Expr.Binary(expr, subOperator, right)
            )
        }
        return null
    }

    // ---------- Expr ----------
    private fun expression(): Expr = or()

    private fun or(): Expr {
        var expr = and()
        var errorRight: Expr? = null

        while (check(TokenType.OR_OR)) {
            val operator = next()

            val right = and()
            if (right is Expr.Binary && right.operator.type == TokenType.AND_AND) {
                errorRight = right
            }
            expr = Expr.Binary(expr, operator, right)
        }
        errorRight?.let {
            error("不明确关系的逻辑运算表达式，请添加括号")
                .point(expr.span.start, errorRight.span.end, "")
        }

        return expr
    }

    private fun and(): Expr {
        var expr = equality()

        while (check(TokenType.AND_AND)) {
            val operator = next()

            val right = equality()
            if (right is Expr.Binary && right.operator.type == TokenType.OR_OR) {
                error("不明确关系的逻辑运算表达式，请添加括号")
                    .point(expr.span.start, right.span.end, "")
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

        if (check(TokenType.EQ_OPERATORS)) {
            val operator = next()
            val right = comparison()
            expr = Expr.Binary(expr, operator, right)
        }

        return expr
    }

    /**
     * > >= < <=
     */
    private fun comparison(): Expr {
        var expr = range()

        if (check(TokenType.COMPARISON_OPERATORS)) {
            val operator = next()
            val right = range()
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
            return Expr.Range(between(operator, right), null, operator, right)
        }

        // expr
        var expr = addAndSub()

        // expr :< ...    expr := ...
        if (!isStmtEnd && check(TokenType.RANGE_OPERATORS)) {
            val operator = next()

            // expr :<    expr :=
            if (!check(TokenType.LITERALS) && !check(TokenType.IDENTIFIER) && !check(TokenType.LPAREN)) {
                return Expr.Range(between(operator, operator), null, operator, null)
            }

            // expr :< expr    expr := expr
            val right = addAndSub()
            expr = Expr.Range(between(expr, right), expr, operator, right)
        }

        return expr
    }

    private fun addAndSub(): Expr {
        var expr = mulAndDiv()

        while (check(TokenType.ADD_SUB_OPERATORS)) {
            val operator = next()
            val right = mulAndDiv()
            expr = Expr.Binary(expr, operator, right)
        }

        return expr
    }

    private fun mulAndDiv(): Expr {
        var expr = pow()

        while (check(TokenType.MUL_DIV_OPERATORS)) {
            val operator = next()
            val right = pow()
            expr = Expr.Binary(expr, operator, right)
        }

        return expr
    }

    private fun pow(): Expr {
        var expr = unary()

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
            return Expr.Unary(operator, right)
        }

        return suffixExpr()
    }

    private fun suffixExpr(): Expr {
        var expr = primary()

        while (true) {
            when {
                check(TokenType.LBRACKET) -> { // 对列表的索引或切片
                    val lBracket = next()

                    val index = expression()
                    val rBracket =
                        consume(TokenType.RBRACKET) { e: Diagnostic -> e.info(lBracket, "解析`数组索引`时出现错误") }

                    expr = if (rBracket != null) {
                        Expr.Index(between(lBracket, rBracket), expr, index)
                    } else {
                        Expr.Index(between(lBracket, index), expr, index)
                    }
                    continue
                }

                check(TokenType.LPAREN) -> { // 函数调用
                    val lParen = next()

                    val arguments = Seq<Expr>(8)
                    while (true) {
                        if (check(TokenType.RPAREN)) {
                            expr = Expr.Call(between(expr, next()), expr, arguments)
                            break
                        }
                        val innerExpr = expression()
                        if (innerExpr is ErrorExpr) {
                            error("期望`)`作为函数调用参数末尾")
                                .info(lParen, "参数开头")
                                .point(lookAhead(0), "末尾")
                            expr = Expr.Call(between(expr, arguments.lastOrNull() ?: lParen), expr, arguments)
                            break
                        }
                        arguments.add(innerExpr)
                        match(TokenType.COMMA) // 可选逗号
                    }
                    continue
                }

                check(TokenType.DOT) -> { // 访问类的元素
                    val dot = next()

                    val id =
                        consume(TokenType.IDENTIFIER) { e: Diagnostic ->
                            e.info(dot, "解析`类元素访问`时出现错误")
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

        } else if (check(TokenType.IDENTIFIER)) {
            return Expr.Identifier(next())

        } else if (match(TokenType.LPAREN)) {
            val expr = expression()
            consume(TokenType.RPAREN)
            return expr

        } else if (check(TokenType.LBRACE)) {
            val lBrace = next()
            val elements = seq(
                { expression() },
                EnumSet.of(TokenType.COMMA, TokenType.NEWLINE),
                { true },
                { error(text = "期望空格或`,`或换行符作为数组分隔符").info(obj = lBrace, text = "数组开头") },
                TokenType.RBRACE,
                true,
                { error("期望`}`作为数组末尾").info(lBrace, "数组开头") }
            )
            return Expr.Array(between(lBrace, prevToken), elements)
        }

        error("期望表达式").point(lookAhead(0), "")
        return ErrorExpr(next().span)
    }

    /**
     * 传入[TokenType.IDENTIFIER]/[TokenType.LITERALS]后尝试解析类型注解，
     * 失败则返回[subject]
     * @param subject 已被消耗的[TokenType.IDENTIFIER]/[TokenType.LITERALS]
     */
    private fun annotation(subject: Expr): Expr {
        // id :
        if (!isStmtEnd && match(TokenType.COLON)) {
            val enums = anonymousEnum()

            if (!enums.isEmpty) return Expr.Annotation(subject, enums)
            // 如果标注数量为0，视作Identifier
        }
        return subject
    }

    private fun anonymousEnum(): Seq<Expr> {
        val enums = Seq<Expr>(3)
        // ?
        if (check(TokenType.QUESTION_MARK)) {
            enums.add(Expr.Identifier(Token(next().span, TokenType.IDENTIFIER, "Null")))
            if (check(TokenType.OR)) {
                warning("多余的`|`")
                    .point(next(), "")
            }
        }

        // tuple1 | tuple2 ...
        while (!isAtEnd) {
            if (check(TokenType.LPAREN)) {
                enums.add(tuple())
            } else {
                consume(TokenType.IDENTIFIER)?.let {
                    enums.add(Expr.Identifier(it))
                }
            }
            if (!match(TokenType.OR)) break
        }

        return enums
    }

    /**
     * 元组，先`check(TokenType.LPAREN)`再调用
     */
    private fun tuple(): Expr {
        val lParen = next()
        val elements = seq(
            { expression() },
            EnumSet.of(TokenType.COMMA, TokenType.NEWLINE),
            { true },
            { error("期望`,`或换行符作为元组分隔符").info(lParen, "元组开头") },
            TokenType.RPAREN,
            true,
            { error("期望`)`作为元组末尾").info(lParen, "元组开头") }
        )
//         TODO style提示
        if (elements.size == 1) return elements[0]
        return Expr.Tuple(between(lParen, prevToken), elements)
    }

    /**
     * 解析一组连续表达式序列
     * @param elementProv 解析并返回一个序列元素，无法解析时请返回[ErrorExpr]/`null`以调用恢复，务必消耗元素，否则死循环
     * @param separators 分隔符，方法内已有自带的分隔符[matchStmtEnd]，可填`setOf()`以作空格
     * @param isSeparatorOptional 是否允许省略分隔符
     * @param missSeparator 当`expect(end)`失败时使用missSeparator报错，方法自动调用[Diagnostic.point]
     * @param end 序列结束标志
     * @param consumeEnd 是否消耗[end]，`false`时不强求[end]出现
     * @param missEnd 错误恢复到EOF时调用，[consumeEnd]为`false`时不使用，方法自动调用[Diagnostic.point]
     */
    private fun seq(
        elementProv: Prov<Expr?>,
        separators: EnumSet<TokenType>,
        isSeparatorOptional: Boolp,
        missSeparator: Prov<Diagnostic>,
        end: TokenType,
        consumeEnd: Boolean,
        missEnd: Prov<Diagnostic>? = null,
    ): Seq<Expr> {
        val seq = Seq<Expr>()
        while (!check(end)) {
            val snapshot = createSnapshot()
            val element = elementProv.get()
            if (element == null || element is ErrorExpr) {
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
                if (check(end)) break
                // 没有结束符，可能是漏掉了分隔符，报错并恢复
                if (!isAtEnd) {
                    missSeparator.get().point(lookAhead(0), "")
                }
            }
            // 解析失败，开始恢复
            when (val type = recoverByTokenTree(separators + end)) {
                end -> break

                TokenType.EOF -> {
                    if (consumeEnd) {
                        missEnd?.get()?.point(lookAhead(0), "")
                    }
                    return seq
                }

                else -> if (separators.contains(type)) continue else kotlin.error("Unreachable")
            }
        }
        if (consumeEnd) next()
        return seq
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

        error("期望$type")
            .point(lookAhead(0), type.toString())
        return null
    }

    /**
     * 若下一个token不是指定类型的则返回null并报告错误并将错误输入Cons，否则返回该token。
     */
    private fun expect(type: TokenType, cons: Cons<Diagnostic>): Token? {
        if (check(type)) return lookAhead(0)

        cons.get(
            error("期望$type")
                .point(lookAhead(0), type.toString()),
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
     */
    private fun consumeStmtEnd(): Boolean {
        if (matchStmtEnd()) return true
        // 如果没有找到，报告错误
        error("缺少换行或分号作为语句结束符")
            .point(lookAhead(0), "")
        return false
    }

    /**
     * 若下一个token不是指定类型的则报告错误并返回null，否则返回该token并推进
     */
    private fun consume(type: TokenType): Token? {
        if (check(type)) return next()

        error("期望$type")
            .point(lookAhead(0), type.toString())
        return null
    }

    /**
     * 若下一个token不是指定类型的则报告错误并返回null，否则返回该token并推进
     */
    private fun consume(type: TokenType, cons: Cons<Diagnostic>): Token? {
        if (check(type)) return next()

        cons.get(
            error("期望$type")
                .point(lookAhead(0), type.toString()),
        )
        return null
    }

    //    private Res<Token> consume(Set<TokenType> types) {
    //        if(check(types)) return new Ok<> (next());
    //        StringBuilder sbd = new StringBuilder();
    //        for(TokenType type : types) {
    //            sbd.append(type.toString()).append(" ");
    //        }
    //        return new Err<>(error("期望TokenType")
    //                .point(lookAhead(), sbd.toString())
    //        );
    //    }
    //
    //    private Res<Token> consume(TokenType type, Runnable r) {
    //        if(check(type)) return new Ok<>(next());
    //        Diagnostic.ParserDiag e = (Diagnostic.ParserDiag) error("期望字符").point(lookAhead(), type.toString());
    //        r.run();
    //        return new Err<>(e);
    //    }
    // ---------- 错误恢复方法 ----------

    /**
     * 错误恢复，扫描直到期望的TokenType，但不会消耗，按照Token树解析，不考虑已闭合的定界符
     */
    private fun recoverByTokenTree(expected: Set<TokenType>, addition: TokenType? = null): TokenType {
        val delimiters = Seq<TokenType>()
        while (true) {
            val type = lookAhead(0).type
            if (delimiters.isEmpty && (expected.contains(type) || addition == type)) return type
            when (type) {
                TokenType.EOF -> return TokenType.EOF

                TokenType.LPAREN -> delimiters.add(type)
                TokenType.LBRACKET -> delimiters.add(type)
                TokenType.LBRACE -> delimiters.add(type)

                TokenType.RPAREN -> {
                    if (!delimiters.isEmpty && delimiters.peek() == TokenType.LPAREN) {
                        delimiters.pop()
                    }
                }

                TokenType.RBRACKET -> {
                    if (!delimiters.isEmpty && delimiters.peek() == TokenType.LBRACKET) {
                        delimiters.pop()
                    }
                }

                TokenType.RBRACE -> {
                    if (!delimiters.isEmpty && delimiters.peek() == TokenType.LBRACE) {
                        delimiters.pop()
                    }
                }

                else -> Unit
            }
            next()
        }
    }

    /**
     * 错误恢复，扫描直到期望的TokenType，但不会消耗
     */
    private fun recover(expecteds: EnumSet<TokenType>) {
        while (!isAtEnd) {
            if (check(expecteds)) return
            next()
        }
    }

    private fun createSnapshot(): Snapshot {
        return Snapshot(
            input.deepCopy(),
            lexer.createSnapshot(),
            problems.createSnapshot()
        )
    }

    private fun restoreSnapshot(snapshot: Snapshot) {
        input = snapshot.inputSnapshot
        lexer.restoreSnapshot(snapshot.lexerSnapshot)
        problems.restoreSnapshot(snapshot.problemsSnapshot)
    }

    // ---------- 类生成方法 ----------
    private fun token(type: TokenType, from: Token): Token {
        return Token(from.span, type)
    }

    /**
     * 生成当前文件的span
     * @param from 起始
     * @param to 末尾
     */
    private fun between(from: Spanned, to: Spanned): Span {
        return Span(from.span().start, to.span().end)
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
        val e = ParserDiag(sourceMap, text, Diagnostic.DiagLevel.ERROR)
        problems.addError(e)
        return e
    }

    private fun warning(text: String): ParserDiag {
        val w = ParserDiag(sourceMap, text, Diagnostic.DiagLevel.WARNING)
        problems.addWarning(w)
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
        var prevToken: Token = Token(Span(0, 0), TokenType.UNKNOWN)

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

    private data class Snapshot(
        val inputSnapshot: InputWindow,
        val lexerSnapshot: Lexer.LexerSnapshot,
        val problemsSnapshot: DiagCollector.DiagCollectorSnapshot
    )
}
