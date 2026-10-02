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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * 结构体的端到端测试：语法（[Parser]）→ 名称解析（[Resolver]）→ 类型推断（[TypeInferencer]）。
 *
 * 语言约定（与使用者确认过的语义）：
 * - 构造一律是**函数调用** `Point(1.0, 2.0)`（不引入 `Point { x: 1.0 }` 字面量语法）；
 * - 字段可写默认值 `x : Num = 0.0`，也可省略类型 `x = 0`（字段类型由默认值推断）；
 * - 有默认值的字段在构造时可省略（只能按尾部省略），无默认值的字段必须写出来；
 * - 方法用**隐式 `self`**：方法体内直接写字段名（`x`）或调同结构体方法（`one()`），
 *   调用写成 `p.method(...)`；接收者不可写（`self` 不进作用域）；
 * - 泛型结构体 `Wrapper<T>` 与泛型枚举走同一套类型方案机制。
 *
 * 注意类型：`Num` 字段要写浮点字面量（`1.0`），整数字面量 `1` 是 `Int`，
 * 与 `Num` 是不同类型（本语言不做数值字面量的隐式提升）。
 */
class StructTest {
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

    /** 解析 → 名称解析 → 类型推断，返回错误数 */
    private fun analyze(source: String): Int {
        context.diagHandler.clear()
        val sourceFile = SourceFile(source)
        val ast = parser.parse(sourceFile)
        val result = resolver.resolve(ast, sourceFile)
        inferencer.analyze(result, sourceFile)
        return context.diagHandler.errorNum()
    }

    private fun errorMessages(source: String): String {
        analyze(source)
        return context.diagHandler.errors.joinToString("\n") { it.message }
    }

    // ========== 语法 ==========

    @Test
    fun `parse struct with typed field default and method`() {
        val ast = parse(
            """
            struct Point {
                x : Num = 0.0
                y = 0
                fn dist(a : Num) -> Num { }
            }
            """.trimIndent()
        )

        val expected = Stmt.Struct(
            span,
            Expr.Identifier(token(TokenType.IDENTIFIER, "Point")),
            null,
            Seq.with(
                Stmt.Struct.StructField(
                    span,
                    Expr.Identifier(token(TokenType.IDENTIFIER, "x")),
                    Expr.Annotation(
                        Expr.Identifier(token(TokenType.IDENTIFIER, "x")),
                        Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "Num"))),
                    ),
                    Expr.Literal(token(TokenType.NUM, 0.0)),
                ),
                Stmt.Struct.StructField(
                    span,
                    Expr.Identifier(token(TokenType.IDENTIFIER, "y")),
                    null,
                    Expr.Literal(token(TokenType.INT, 0.0)),
                ),
            ),
            Seq.with(
                Stmt.Fn(
                    span,
                    token(TokenType.IDENTIFIER, "dist"),
                    null,
                    Seq.with(
                        Expr.Annotation(
                            Expr.Identifier(token(TokenType.IDENTIFIER, "a")),
                            Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "Num"))),
                        ),
                    ),
                    Seq.with(Expr.Identifier(token(TokenType.IDENTIFIER, "Num"))),
                    Stmt.Block(span, Seq(0)),
                ),
            ),
        )
        assertProgram(ast, expected)
    }

    @Test
    fun `field without type and without default is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `compound assignment as field default is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x : Num += 1.0
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `field default cannot reference a sibling field`() {
        // 默认值里裸写字段名 = 引用另一个字段的默认值（会形成依赖环），故按「未声明的标识符」拒绝
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x : Num = 0.0
                    y : Num = x
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `method name clashing with a field is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x : Num = 0.0
                    fn x() -> Num { }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `duplicate method name is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    fn x() -> Num { }
                    fn x() -> Num { }
                }
                """.trimIndent()
            )
        )
    }

    // ========== 构造 ==========

    @Test
    fun `positional construction type checks fields`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    x : Num
                    y : Num
                }
                set p = Point(1.0, 2.0)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `field type mismatch is reported at the argument`() {
        val messages = errorMessages(
            """
            struct Point {
                x : Num
                y : Num
            }
            set p = Point("s", 2.0)
            """.trimIndent()
        )
        assertTrue(
            messages.contains(I18N.bundle.format("diag.type-mismatch", "Str", "Num")),
            "应在实参上报告类型不匹配，实际:\n$messages",
        )
    }

    @Test
    fun `integer literal is not implicitly a Num field`() {
        // 本语言不做数值字面量提升：`Num` 字段传整数 `1` 报类型不匹配
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x : Num
                }
                set p = Point(1)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `too many constructor arguments are rejected`() {
        val messages = errorMessages(
            """
            struct Point {
                x : Num
                y : Num
            }
            set p = Point(1.0, 2.0, 3.0)
            """.trimIndent()
        )
        assertTrue(
            messages.contains(I18N.bundle.format("diag.struct-field-count", "Point", 2, 3)),
            "应报告实参过多，实际:\n$messages",
        )
    }

    @Test
    fun `missing field without default is rejected`() {
        val messages = errorMessages(
            """
            struct Point {
                x : Num
                y : Num
            }
            set p = Point(1.0)
            """.trimIndent()
        )
        assertTrue(
            messages.contains(I18N.bundle.format("diag.struct-missing-field", "Point", "y")),
            "应报告缺少字段，实际:\n$messages",
        )
    }

    @Test
    fun `default field can be omitted positionally`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    x : Num = 0.0
                    y : Num = 0.0
                }
                set a = Point()
                set b = Point(1.0)
                set c = Point(1.0, 2.0)
                """
            )
        )
    }

    @Test
    fun `field type is inferred from the default value`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    x = 0
                }
                set p = Point(1)
                set n : Int = p.x
                """
            )
        )
    }

    @Test
    fun `inferred field type still rejects a wrong argument`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x = 0
                }
                set p = Point("s")
                """.trimIndent()
            )
        )
    }

    // ========== 字段访问 ==========

    @Test
    fun `field access yields the declared field type`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    x : Num
                    y : Num
                }
                set p = Point(1.0, 2.0)
                set n : Num = p.x
                """
            )
        )
    }

    @Test
    fun `field access with wrong annotation is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x : Num
                    y : Num
                }
                set p = Point(1.0, 2.0)
                set n : Str = p.x
                """.trimIndent()
            )
        )
    }

    @Test
    fun `unknown field is rejected`() {
        val messages = errorMessages(
            """
            struct Point {
                x : Num
            }
            set p = Point(1.0)
            set n = p.z
            """.trimIndent()
        )
        assertTrue(
            messages.contains(I18N.bundle.format("diag.no-such-member", "Point", "z")),
            "应报告没有这个成员，实际:\n$messages",
        )
    }

    @Test
    fun `reading a field off the type name is rejected`() {
        val messages = errorMessages(
            """
            struct Point {
                x : Num
            }
            set n = Point.x
            """.trimIndent()
        )
        assertTrue(
            messages.contains(I18N.bundle.format("diag.struct-type-field-access", "Point", "x")),
            "应报告类型上没有字段值，实际:\n$messages",
        )
    }

    @Test
    fun `member access on a non struct value is rejected`() {
        val messages = errorMessages("set n = 1.0 .x")
        assertTrue(
            messages.contains(I18N.bundle.format("diag.member-on-non-struct", "x", "Num")),
            "应报告非结构体值上没有成员，实际:\n$messages",
        )
    }

    // ========== 方法 ==========

    @Test
    fun `method body reads fields without qualification`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    x : Num
                    y : Num
                    fn sum() -> Num { return x + y }
                }
                set p = Point(1.0, 2.0)
                set n : Num = p.sum()
                """
            )
        )
    }

    @Test
    fun `method body can call a sibling method without qualification`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    fn one() -> Num { return 1.0 }
                    fn two() -> Num { return one() }
                }
                set p = Point()
                set n : Num = p.two()
                """
            )
        )
    }

    @Test
    fun `method body can write self field explicitly`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    x : Num
                    fn get() -> Num { return self.x }
                }
                set p = Point(1.0)
                set n : Num = p.get()
                """
            )
        )
    }

    @Test
    fun `method call checks the receiver type`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x : Num
                    fn get() -> Num { return x }
                }
                set s = "not a point"
                set n = s.get()
                """.trimIndent()
            )
        )
    }

    @Test
    fun `method call checks argument count`() {
        val messages = errorMessages(
            """
            struct Point {
                x : Num
                fn plus(a : Num, b : Num) -> Num { return x + a + b }
            }
            set p = Point(1.0)
            set n = p.plus(1.0)
            """.trimIndent()
        )
        assertTrue(
            messages.contains(I18N.bundle.format("diag.struct-method-arg-count", "plus", 2, 1)),
            "应报告实参数量不符，实际:\n$messages",
        )
    }

    @Test
    fun `method call checks argument type`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x : Num
                    fn plus(a : Num) -> Num { return x + a }
                }
                set p = Point(1.0)
                set n = p.plus("s")
                """.trimIndent()
            )
        )
    }

    @Test
    fun `method result type flows into the call site`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    fn flag() -> Bool { return true }
                }
                set p = Point()
                if p.flag() { set n = 1 }
                """
            )
        )
    }

    @Test
    fun `method reference keeps the receiver type`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    x : Num
                    fn get() -> Num { return x }
                }
                set p = Point(1.0)
                set g = p.get
                set n : Num = g()
                """
            )
        )
    }

    @Test
    fun `field name is not visible outside the method`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x : Num = 0.0
                }
                set n = x
                """.trimIndent()
            )
        )
    }

    // ========== 泛型 ==========

    @Test
    fun `generic struct instantiates per construction site`() {
        assertEquals(
            0,
            analyze(
                """
                struct Wrapper<T> {
                    v : T
                }
                set a = Wrapper(1)
                set b = Wrapper("s")
                set n : Int = a.v
                set s : Str = b.v
                """
            )
        )
    }

    @Test
    fun `generic struct field keeps the type argument`() {
        assertEquals(
            1,
            analyze(
                """
                struct Wrapper<T> {
                    v : T
                }
                set a = Wrapper(1)
                set s : Str = a.v
                """.trimIndent()
            )
        )
    }

    @Test
    fun `generic struct appears in annotations`() {
        assertEquals(
            0,
            analyze(
                """
                struct Wrapper<T> {
                    v : T
                }
                fn unwrap(w : Wrapper<Int>) -> Int { return w.v }
                set n = unwrap(Wrapper(1))
                """
            )
        )
    }

    @Test
    fun `explicit type argument on a non generic struct is rejected`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x : Num
                }
                set p = Point<Num>(1.0)
                """.trimIndent()
            )
        )
    }

    // ========== 与枚举/类型系统的协作 ==========

    @Test
    fun `struct type can be used as an annotation`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    x : Num
                    y : Num
                }
                fn length(p : Point) -> Num { return p.x }
                set n = length(Point(1.0, 2.0))
                """
            )
        )
    }

    @Test
    fun `struct annotation rejects a mismatched argument`() {
        assertEquals(
            1,
            analyze(
                """
                struct Point {
                    x : Num
                }
                struct Other {
                    x : Num
                }
                fn f(p : Point) -> Num { return 0.0 }
                set n = f(Other(1.0))
                """.trimIndent()
            )
        )
    }

    @Test
    fun `struct name is not usable as a match variant pattern`() {
        val messages = errorMessages(
            """
            struct Point {
                x : Num
            }
            set p = Point(1.0)
            match p {
                Point.x -> { }
                _ -> { }
            }
            """.trimIndent()
        )
        assertTrue(
            messages.contains(I18N.bundle.format("diag.pattern-not-enum", "Point")),
            "结构体名做变体模式应报「不是枚举」，实际:\n$messages",
        )
    }

    @Test
    fun `constructor is a first class function value`() {
        assertEquals(
            0,
            analyze(
                """
                struct Point {
                    x : Num
                    y : Num
                }
                set f = Point
                set p = f(1.0, 2.0)
                set n : Num = p.x
                """
            )
        )
    }

    // ========== 无字段结构体 ==========

    @Test
    fun `fieldless struct is constructed with an empty argument list`() {
        assertEquals(
            0,
            analyze(
                """
                struct None
                set p = None()
                """
            )
        )
    }

    @Test
    fun `fieldless struct can be instantiated repeatedly`() {
        assertEquals(
            0,
            analyze(
                """
                struct None { }
                set a = None()
                set b = None()
                """
            )
        )
    }

    @Test
    fun `fieldless struct value fits its own annotation`() {
        assertEquals(
            0,
            analyze(
                """
                struct None
                fn take(n : None) -> Int { return 0 }
                set r = take(None())
                """.trimIndent()
            )
        )
    }

    @Test
    fun `fieldless struct name as a bare value is rejected`() {
        // `None` 是类型名：值只能是构造出来的实例（`None()`）
        assertEquals(
            1,
            analyze(
                """
                struct None
                set p = None
                """.trimIndent()
            )
        )
    }

    @Test
    fun `fieldless struct rejects an argument`() {
        assertEquals(
            1,
            analyze(
                """
                struct None
                set p = None(1)
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
