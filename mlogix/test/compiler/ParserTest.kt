package mlogix.compiler

import arc.struct.Seq
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.diagnostic.ProblemCollector
import mlogix.compiler.passes.parsing.Lexer
import mlogix.compiler.passes.parsing.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ParserTest {
    val problems = ProblemCollector()
    val lexer = Lexer(problems)
    val parser = Parser(lexer, problems)

    val span = Span(0, 0, 0)

    @Test
    fun `parse correct AST for addition`() {
        val ast = parser.parse("2 + 3")
        assertEquals(
            ast,
            Stmt.ExprStmt(
                span,
                Expr.Binary(
                    Expr.Literal(token(TokenType.INT, 2.0)),
                    token(TokenType.PLUS),
                    Expr.Literal(token(TokenType.INT, 3.0))
                )
            )
        )

    }

    @Test
    fun `parse set assignment`() {
        val ast = parser.parse("set a = 1")

        val varExpr = Expr.Identifier(token(TokenType.IDENTIFIER, "a"))
        val assign = Stmt.AssignStmt(
            span,
            varExpr,
            token(TokenType.ASSIGN),
            Expr.Literal(token(TokenType.INT, 1.0))
        )
        val expected = Stmt.SetVarStmt(span, varExpr, assign)
        assertEquals(ast, expected)
    }

    @Test
    fun `parse if else`() {
        val ast = parser.parse("if 1 { 2 } else { 3 }")

        val condition = Expr.Literal(token(TokenType.INT, 1.0))
        val thenBranch = Stmt.BlockStmt(span, Seq.with(Stmt.ExprStmt(span, Expr.Literal(token(TokenType.INT, 2.0)))))
        val elseBranch = Stmt.BlockStmt(span, Seq.with(Stmt.ExprStmt(span, Expr.Literal(token(TokenType.INT, 3.0)))))

        val expected = Stmt.IfStmt(span, condition, thenBranch, elseBranch)
        assertEquals(ast, expected)
    }

    @Test
    fun `parse for`() {
        val ast = parser.parse("loop: for i in 1:<links { print(i) }")

        val flag = Expr.Identifier(token(TokenType.IDENTIFIER, "loop"))
        val varDecl = Expr.Identifier(token(TokenType.IDENTIFIER, "i"))
        val expr = Expr.Range(
            span,
            Expr.Literal(token(TokenType.INT, 1.0)),
            token(TokenType.COLON_LESS),
            Expr.Identifier(token(TokenType.IDENTIFIER, "links")),
        )
        val body = Stmt.BlockStmt(
            span,
            Seq.with(
                Stmt.ExprStmt(
                    span,
                    Expr.Call(
                        span,
                        Expr.Identifier(token(TokenType.IDENTIFIER, "print")),
                        Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "i")))
                    )
                )
            )
        )
        val expected = Stmt.ForStmt(span, flag, varDecl, expr, body)
        assertEquals(ast, expected)
    }

    @Test
    fun `parse while`() {
        val ast = parser.parse("loop: while true { continue }")
        val expectedWhile = Stmt.WhileStmt(
            span,
            Expr.Identifier(token(TokenType.IDENTIFIER, "loop")),
            Expr.Literal(token(TokenType.TRUE)),
            Stmt.BlockStmt(span, Seq.with(Stmt.ContinueStmt(span, null)))
        )
        assertEquals(ast, expectedWhile)
    }

    @Test
    fun `parse function declaration`() {
        val ast = parser.parse("fn add(a:?(Num Str), b:d) -> r : ?(Num Int), msg : Str { return a + b }")

        val name = token(TokenType.IDENTIFIER, "add")
        val aParam = Expr.Annotation(
            Expr.Identifier(token(TokenType.IDENTIFIER, "a")),
            Seq.with(
                Expr.Identifier(token(TokenType.IDENTIFIER, "Null")),
                Expr.Tuple(
                    span,
                    Seq.with(
                        Expr.Identifier(token(TokenType.IDENTIFIER, "Num")),
                        Expr.Identifier(token(TokenType.IDENTIFIER, "Str")),
                    )
                )
            ),
        )
        val bParam = Expr.Annotation(
            Expr.Identifier(token(TokenType.IDENTIFIER, "b")),
            Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "d"))),
        )
        val results = Seq.with<Expr>(
            Expr.Annotation(
                Expr.Identifier(token(TokenType.IDENTIFIER, "r")),
                Seq.with(
                    Expr.Identifier(token(TokenType.IDENTIFIER, "Null")),
                    Expr.Tuple(
                        span,
                        Seq.with(
                            Expr.Identifier(token(TokenType.IDENTIFIER, "Num")),
                            Expr.Identifier(token(TokenType.IDENTIFIER, "Int")),
                        )
                    )
                )
            ),
            Expr.Annotation(
                Expr.Identifier(token(TokenType.IDENTIFIER, "msg")),
                Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "Str")))
            )
        )
        val body = Stmt.BlockStmt(
            span, Seq.with(
                Stmt.ReturnStmt(
                    span, Expr.Binary(
                        Expr.Identifier(token(TokenType.IDENTIFIER, "a")),
                        token(TokenType.PLUS),
                        Expr.Identifier(token(TokenType.IDENTIFIER, "b"))
                    )
                )
            )
        )

        val expectedFn = Stmt.FnStmt(span, name, Seq.with(aParam, bParam), results, body)
        assertEquals(ast, expectedFn)
    }

    @Test
    fun `parse function call`() {
        val ast = parser.parse("add(1, 2)")
        val call = Expr.Call(
            span, Expr.Identifier(token(TokenType.IDENTIFIER, "add")), Seq.with(
                Expr.Literal(token(TokenType.INT, 1.0)), Expr.Literal(token(TokenType.INT, 2.0))
            )
        )
        assertEquals(ast, Stmt.ExprStmt(span, call))
    }

    @Test
    fun `parse set with annotation and array literal`() {
        val ast = parser.parse("set a : Int = {1, 2}")

        val varExpr = Expr.Annotation(
            Expr.Identifier(token(TokenType.IDENTIFIER, "a")),
            Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "Int")))
        )
        val array = Expr.Array(
            span, Seq.with(
                Expr.Literal(token(TokenType.INT, 1.0)), Expr.Literal(token(TokenType.INT, 2.0))
            )
        )
        val assign = Stmt.AssignStmt(span, varExpr, token(TokenType.ASSIGN), array)
        val expected = Stmt.SetVarStmt(span, varExpr, assign)
        assertEquals(ast, expected)
    }

    @Test
    fun `parse match`() {
        val ast = parser.parse("match 1 { 1 -> { break } }")
        val matchBranchBody = Stmt.BlockStmt(span, Seq.with(Stmt.BreakStmt(span, null)))
        val branch = Stmt.MatchStmt.MatchBranch(span, Expr.Literal(token(TokenType.INT, 1.0)), matchBranchBody)
        val expectedMatch = Stmt.MatchStmt(span, Expr.Literal(token(TokenType.INT, 1.0)), Seq.with(branch))
        assertEquals(ast, expectedMatch)
    }

    private fun token(type: TokenType, literal: Any? = null): Token {
        return Token(Span(0, 0, 0), type, literal)
    }

    private fun assertEquals(actual: Stmt, vararg stmts: Stmt) {
        assertEquals(Stmt.Program(span, Seq.with(*stmts)), actual, "AST 结构必须匹配")
    }
}
