package mlogix.compiler.semantics

import arc.struct.Seq
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.token.TokenType
import mlogix.compiler.core.type.BuiltinType
import mlogix.compiler.core.type.Type
import mlogix.compiler.support.CompilerTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Rust 风格枚举（变体用 `.` 访问）的端到端测试：
 * 语法（Parser）→ 名称解析（Resolver）→ 类型推断（TypeInferencer），语义部分断言错误数。
 */
class EnumTest : CompilerTestBase() {
    companion object {
        val span = Span(0, 0, 0)
    }

    /** `Option<arg>` 类型 */
    private fun optionOf(arg: Type): Type = Type.App(Type.Con("Option"), Seq.with(arg))

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

        val expected = Stmt.Enum(
            span,
            Expr.Identifier(token(TokenType.IDENTIFIER, "Shape")),
            Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "T"))),
            Seq.with(
                Stmt.Enum.EnumVariant.Unit(span, Expr.Identifier(token(TokenType.IDENTIFIER, "Empty"))),
                Stmt.Enum.EnumVariant.Tuple(
                    span,
                    Expr.Identifier(token(TokenType.IDENTIFIER, "Circle")),
                    Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "Num"))),
                ),
                Stmt.Enum.EnumVariant.Struct(
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
        val expected = Stmt.Enum(
            span,
            Expr.Identifier(token(TokenType.IDENTIFIER, "Color")),
            null,
            Seq.with(
                Stmt.Enum.EnumVariant.Unit(span, Expr.Identifier(token(TokenType.IDENTIFIER, "Red"))),
                Stmt.Enum.EnumVariant.Unit(span, Expr.Identifier(token(TokenType.IDENTIFIER, "Green"))),
                Stmt.Enum.EnumVariant.Unit(span, Expr.Identifier(token(TokenType.IDENTIFIER, "Blue"))),
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
        val assign = Stmt.Assign(span, varExpr, token(TokenType.ASSIGN), call)
        assertProgram(ast, Stmt.SetVar(span, varExpr, assign))
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

    /**
     * 求解后的类型必须写回符号：`Option.Some("aaa")` 在 walk 阶段得到的是**含未求解变量**的
     * `Option<Var(k)>`（载荷类型由约束在求解期才定下来），而符号上最终挂的必须是终态 `Option<Str>`。
     *
     * 回归：旧实现在写回阶段只处理「类型本身恰好是变量」的符号，于是这类复合类型被整体跳过，
     * `b` 的类型停在 `Option<Var(2)>`。
     */
    @Test
    fun `solved variant type is written back to the variable symbol`() {
        val table = analyzeSymbols(
            """
            enum Option<T> {
                None
                Some(T)
            }
            set b = Option.Some("aaa")
            """.trimIndent()
        )
        assertEquals(optionOf(BuiltinType.Str), typeOf(table, "b"))
    }

    @Test
    fun `nested variant type is written back fully solved`() {
        val table = analyzeSymbols(
            """
            enum Option<T> {
                None
                Some(T)
            }
            set n = Option.Some(Option.Some(1))
            """.trimIndent()
        )
        assertEquals(optionOf(optionOf(BuiltinType.Int)), typeOf(table, "n"))
    }

    @Test
    fun `each variable keeps its own solved type`() {
        val table = analyzeSymbols(
            """
            enum Option<T> {
                None
                Some(T)
            }
            set a = Option.Some(1)
            set b = Option.Some("s")
            """.trimIndent()
        )
        assertEquals(optionOf(BuiltinType.Int), typeOf(table, "a"))
        assertEquals(optionOf(BuiltinType.Str), typeOf(table, "b"))
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
