package mlogix.compiler.support

import arc.struct.Seq
import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.symbol.SymbolTable
import mlogix.compiler.core.token.Token
import mlogix.compiler.core.type.Type
import mlogix.compiler.diagnostic.DiagHandler
import mlogix.compiler.pipeline.CompilationContext
import mlogix.util.I18N
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll

/**
 * 所有编译器测试的公共基类。
 *
 * 只放「每个测试都会用到」的东西：
 * - 统一的 I18N 初始化；
 * - 统一的分析入口（[analyzeFile] / [analyze] / [parse] / [tokenize]）；
 * - 常用的诊断/类型断言辅助。
 *
 * 具体测试类不应该再自己 new Lexer/Parser/Resolver/TypeInferencer，也不应该再
 * 复制 private fun analyze / typeOf / assertErrorMentions。
 */
abstract class CompilerTestBase {
    protected val fixture = CompilerFixture()

    protected val context: CompilationContext
        get() = fixture.context

    protected val diagHandler: DiagHandler
        get() = fixture.diagHandler

    protected fun tokenize(source: String): Seq<Token> = fixture.tokenize(source)

    protected fun parse(source: String): Stmt = fixture.parse(source)

    /** 完整跑一遍解析 → 名称解析 → 类型推断。 */
    protected fun analyzeFile(source: String): AnalysisResult = fixture.analyze(source)

    /** 只关心错误数的旧式入口；新测试优先用 [analyzeFile] + 诊断断言。 */
    protected fun analyze(source: String): Int = analyzeFile(source).errorNum()

    /** 分析后返回源码本身，方便直接写 textOf(source, span)。 */
    protected fun analyzeSource(source: String): String {
        analyzeFile(source)
        return source
    }

    protected fun analyzeSymbols(source: String): SymbolTable = analyzeFile(source).symbolTable

    protected fun errors(source: String): Int = analyze(source)

    protected fun warnings(source: String): Int = analyzeFile(source).warningNum()

    protected fun errorMessages(source: String): String =
        analyzeFile(source).errors.joinToString("\n") { it.message }

    /** 求解后符号 name 的类型。 */
    protected fun typeOf(table: SymbolTable, name: String): Type {
        val symbol = table.all().firstOrNull { it.name == name }
        assertNotNull(symbol, "符号 `$name` 必须存在")
        return symbol!!.type
    }

    protected fun textOf(source: String, span: Span): String =
        source.substring(span.start, span.end)

    /** 断言错误消息里包含某个 I18N key 格式化后的文案。 */
    protected fun assertErrorMentions(source: String, vararg keyAndArgs: Any) {
        val result = analyzeFile(source)
        val key = keyAndArgs[0] as String
        val args = keyAndArgs.drop(1).toTypedArray()
        val expected = format(key, args)
        val messages = result.errors.joinToString("\n") { it.message }
        assertTrue(messages.contains(expected), "错误消息应包含「$expected」，实际:\n$messages")
    }

    /** 断言告警消息里包含某个 I18N key 格式化后的文案。 */
    protected fun assertWarningMentions(source: String, vararg keyAndArgs: Any) {
        val result = analyzeFile(source)
        val key = keyAndArgs[0] as String
        val args = keyAndArgs.drop(1).toTypedArray()
        val expected = format(key, args)
        val messages = result.warnings.joinToString("\n") { it.message }
        assertTrue(messages.contains(expected), "告警应包含「$expected」，实际:\n$messages")
    }

    private fun format(key: String, args: Array<out Any>): String =
        if (args.isEmpty()) I18N.bundle.get(key) else I18N.bundle.format(key, *args)

    companion object {
        @BeforeAll
        @JvmStatic
        fun installI18n() {
            TestI18n.install()
        }
    }
}
