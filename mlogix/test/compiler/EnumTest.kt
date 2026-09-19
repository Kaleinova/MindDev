package mlogix.compiler

import arc.files.Fi
import arc.struct.Seq
import arc.util.I18NBundle.createBundle
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.CompilerConfig
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.passes.parsing.Lexer
import mlogix.compiler.passes.parsing.Parser
import mlogix.compiler.passes.resolution.Resolver
import mlogix.compiler.passes.typing.TypeInferencer
import mlogix.compiler.pipeline.CompilationContext
import mlogix.util.I18N
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Rust 风格枚举（变体用 `.` 访问）的端到端测试：
 * 语法（[Parser]）→ 名称解析（[Resolver]）→ 类型推断（[TypeInferencer]），语义部分断言错误数。
 */
class EnumTest {
    private val context = CompilationContext(DiagHandler(), CompilerConfig())
    private val lexer = Lexer(context)
    private val parser = Parser(lexer, context)
    private val resolver = Resolver(context)
    private val inferencer = TypeInferencer(context)

    companion object {
        val span = Span(0, 0, 0)

        @BeforeAll
        @JvmStatic
        fun init() {
            val projectDirectory = Fi.get(System.getProperty("user.dir"))
            I18N.bundle = createBundle(projectDirectory.child("assets/bundles/bundle"))
        }
    }

    private fun parse(source: String): Stmt {
        context.diagHandler.clear()
        return parser.parse(SourceFile(source))
    }

    /** 解析 → 名称解析 → 类型推断，返回推断后的错误数（parser.parse 会先清空诊断） */
    private fun analyze(source: String): Int {
        context.diagHandler.clear()
        val sourceFile = SourceFile(source)
        val ast = parser.parse(sourceFile)
        val result = resolver.resolve(ast, sourceFile)
        inferencer.analyze(result, sourceFile)
        return context.diagHandler.errorNum()
    }

    // ========== 语法 ==========

    @Test
    fun `parse unit tuple and struct variants`() {
        val ast = parse(
            """
            enum Shape<T> {
                Empty
                Circle(Num)
                Rect { width: Num, height: Num }
            }
            """.trimIndent()
        )

        val expected = Stmt.EnumStmt(
            span,
            Expr.Identifier(token(TokenType.IDENTIFIER, "Shape")),
            Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "T"))),
            Seq.with(
                Stmt.EnumStmt.EnumVariant.Unit(span, Expr.Identifier(token(TokenType.IDENTIFIER, "Empty"))),
                Stmt.EnumStmt.EnumVariant.Tuple(
                    span,
                    Expr.Identifier(token(TokenType.IDENTIFIER, "Circle")),
                    Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "Num"))),
                ),
                Stmt.EnumStmt.EnumVariant.Struct(
                    span,
                    Expr.Identifier(token(TokenType.IDENTIFIER, "Rect")),
                    Seq.with(
                        Expr.Annotation(
                            Expr.Identifier(token(TokenType.IDENTIFIER, "width")),
                            Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "Num"))),
                        ),
                        Expr.Annotation(
                            Expr.Identifier(token(TokenType.IDENTIFIER, "height")),
                            Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "Num"))),
                        ),
                    ),
                ),
            ),
        )
        assertProgram(ast, expected)
    }

    @Test
    fun `parse comma separated variants on one line`() {
        val ast = parse("enum Color { Red, Green, Blue }")
        val expected = Stmt.EnumStmt(
            span,
            Expr.Identifier(token(TokenType.IDENTIFIER, "Color")),
            null,
            Seq.with(
                Stmt.EnumStmt.EnumVariant.Unit(span, Expr.Identifier(token(TokenType.IDENTIFIER, "Red"))),
                Stmt.EnumStmt.EnumVariant.Unit(span, Expr.Identifier(token(TokenType.IDENTIFIER, "Green"))),
                Stmt.EnumStmt.EnumVariant.Unit(span, Expr.Identifier(token(TokenType.IDENTIFIER, "Blue"))),
            ),
        )
        assertProgram(ast, expected)
    }

    @Test
    fun `parse variant access with dot`() {
        val ast = parse("set c = Color.Rgb(1.0, 2.0, 3.0)")

        val varExpr = Expr.Identifier(token(TokenType.IDENTIFIER, "c"))
        val call = Expr.Call(
            span,
            Expr.Get(
                Expr.Identifier(token(TokenType.IDENTIFIER, "Color")),
                Expr.Identifier(token(TokenType.IDENTIFIER, "Rgb")),
            ),
            Seq.with(
                Expr.Literal(token(TokenType.NUM, 1.0)),
                Expr.Literal(token(TokenType.NUM, 2.0)),
                Expr.Literal(token(TokenType.NUM, 3.0)),
            ),
        )
        val assign = Stmt.AssignStmt(span, varExpr, token(TokenType.ASSIGN), call)
        assertProgram(ast, Stmt.SetVarStmt(span, varExpr, assign))
    }

    // ========== 语义：正例 ==========

    @Test
    fun `unit variant access yields the enum type`() {
        assertEquals(
            0,
            analyze(
                """
                enum Color {
                    Red
                    Green
                }
                set c = Color.Red
                set d = Color.Green
                """.trimIndent()
            )
        )
    }

    @Test
    fun `tuple and struct variant constructors check payload`() {
        assertEquals(
            0,
            analyze(
                """
                enum Shape {
                    Circle(Num)
                    Rgb(Num, Num, Num)
                    Rect { width: Num, height: Num }
                }
                set a = Shape.Circle(1.0)
                set b = Shape.Rgb(1.0, 2.0, 3.0)
                set c = Shape.Rect(1.0, 2.0)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `variants are namespaced by their enum`() {
        assertEquals(
            0,
            analyze(
                """
                enum A {
                    None
                }
                enum B {
                    None
                }
                set a = A.None
                set b = B.None
                """.trimIndent()
            )
        )
    }

    @Test
    fun `generic enum variants are polymorphic`() {
        assertEquals(
            0,
            analyze(
                """
                enum Option<T> {
                    None
                    Some(T)
                }
                set a = Option.Some(1)
                set b = Option.Some("s")
                set c = Option.None
                """.trimIndent()
            )
        )
    }

    @Test
    fun `explicit enum type arguments instantiate the payload`() {
        assertEquals(
            0,
            analyze(
                """
                enum Option<T> {
                    None
                    Some(T)
                }
                set a = Option<Int>.Some(1)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `variant payload unifies with annotated enum type`() {
        assertEquals(
            0,
            analyze(
                """
                enum Option<T> {
                    None
                    Some(T)
                }
                fn unwrap(x: Option<Int>) -> Int { return 0 }
                set r = unwrap(Option.Some(1))
                """.trimIndent()
            )
        )
    }

    @Test
    fun `payload variant is a first class constructor function`() {
        assertEquals(
            0,
            analyze(
                """
                enum Shape {
                    Circle(Num)
                }
                set f = Shape.Circle
                set s = f(1.0)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `recursive generic enum`() {
        assertEquals(
            0,
            analyze(
                """
                enum List<T> {
                    Nil
                    Cons(T, List<T>)
                }
                set l = List.Cons(1, List.Nil)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `unit variant of generic enum infers payload from context`() {
        assertEquals(
            0,
            analyze(
                """
                enum Option<T> {
                    None
                    Some(T)
                }
                fn isNone(x: Option<Int>) -> Int { return 0 }
                set r = isNone(Option.None)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `enum type can be used as an annotation`() {
        assertEquals(
            0,
            analyze(
                """
                enum Color {
                    Red
                }
                set c : Color = Color.Red
                """.trimIndent()
            )
        )
    }

    @Test
    fun `enum variant can be used as a match pattern`() {
        assertEquals(
            0,
            analyze(
                """
                enum Color {
                    Red
                    Green
                }
                set c = Color.Red
                match c {
                    Color.Red -> { }
                    Color.Green -> { }
                }
                """.trimIndent()
            )
        )
    }

    // ========== 语义：反例 ==========

    @Test
    fun `unknown variant is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                enum Color {
                    Red
                }
                set c = Color.Purple
                """.trimIndent()
            )
        )
    }

    @Test
    fun `variant name is not visible without the enum prefix`() {
        assertEquals(
            1,
            analyze(
                """
                enum Color {
                    Red
                }
                set c = Red
                """.trimIndent()
            )
        )
    }

    @Test
    fun `enum type used as a value is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                enum Color {
                    Red
                }
                set c = Color
                """.trimIndent()
            )
        )
    }

    @Test
    fun `variant field count mismatch is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                enum Shape {
                    Rgb(Num, Num, Num)
                }
                set b = Shape.Rgb(1.0, 2.0)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `variant field type mismatch is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                enum Shape {
                    Rgb(Num, Num, Num)
                }
                set b = Shape.Rgb("s", 2.0, 3.0)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `variant payload mismatch against annotated enum type is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                enum Option<T> {
                    None
                    Some(T)
                }
                fn unwrap(x: Option<Int>) -> Int { return 0 }
                set r = unwrap(Option.Some("s"))
                """.trimIndent()
            )
        )
    }

    @Test
    fun `explicit enum type argument count mismatch is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                enum Option<T> {
                    None
                    Some(T)
                }
                set a = Option<Int, Str>.Some(1)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `type arguments on non generic enum are rejected`() {
        assertEquals(
            1,
            analyze(
                """
                enum Color {
                    Red
                }
                fn f(c: Color<Int>) -> Int { return 0 }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `enum type argument count in annotation is checked`() {
        assertEquals(
            1,
            analyze(
                """
                enum Option<T> {
                    None
                }
                fn f(x: Option<Int, Str>) -> Int { return 0 }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `different enum types do not unify`() {
        assertEquals(
            1,
            analyze(
                """
                enum Color {
                    Red
                }
                enum Other {
                    Blue
                }
                fn f(c: Color) -> Int { return 0 }
                set r = f(Other.Blue)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `duplicate variant is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                enum Color {
                    Red
                    Red
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `missing colon in struct variant field is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                enum Shape {
                    Rect { width }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `missing variant separator on one line is rejected`() {
        assertEquals(
            1,
            analyze("enum Color { Red Green }")
        )
    }

    @Test
    fun `unclosed enum body is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                enum Color {
                    Red
                """.trimIndent()
            )
        )
    }

    private fun token(type: TokenType, literal: Any? = null): Token {
        return Token(Span(0, 0, 0), type, literal)
    }

    private fun assertProgram(actual: Stmt, vararg stmts: Stmt) {
        assertEquals(Stmt.Program(span, Seq.with(*stmts)), actual, "AST 结构必须匹配")
    }
}
