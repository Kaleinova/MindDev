package mlogix.compiler.support

import mlogix.compiler.diagnostic.Diagnostic
import mlogix.util.I18N
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 诊断断言 DSL。
 *
 * 目标：新测试不再写 assertEquals(1, errorNum()) 这种「只数个数」的断言，
 * 而是明确到 **哪条诊断（I18N key）+ 哪个源码片段（label 原文）**。
 *
 * 注意：当前 Diagnostic 还没有独立的 code 字段，所以这里先用 I18N key
 * 作为诊断标识。等生产侧引入 DiagnosticCode 后，只需把 key 换成 code，
 * 测试的书写方式不变。
 */
fun AnalysisResult.assertNoErrors() {
    assertEquals(0, errorNum(), "不应有错误，实际错误:\n${errorMessages()}")
}

fun AnalysisResult.assertNoWarnings() {
    assertEquals(0, warningNum(), "不应有告警，实际告警:\n${warningMessages()}")
}

fun AnalysisResult.assertNoDiagnostics() {
    assertNoErrors()
    assertNoWarnings()
}

fun AnalysisResult.assertErrorCount(expected: Int) {
    assertEquals(expected, errorNum(), "错误数不符，实际错误:\n${errorMessages()}")
}

fun AnalysisResult.assertWarningCount(expected: Int) {
    assertEquals(expected, warningNum(), "告警数不符，实际告警:\n${warningMessages()}")
}

/** 断言恰好一条错误，且消息等于 key 格式化后的文案；返回该诊断便于继续断言 label。 */
fun AnalysisResult.assertSingleError(key: String, vararg args: Any): Diagnostic {
    assertErrorCount(1)
    val expected = format(key, args)
    val error = errors[0]
    assertEquals(expected, error.message, "唯一错误的文案不符")
    return error
}

/** 断言至少有一条错误匹配 key；返回第一条匹配项。 */
fun AnalysisResult.assertHasError(key: String, vararg args: Any): Diagnostic {
    val expected = format(key, args)
    val error = errors.firstOrNull { it.message == expected }
    assertTrue(error != null, "应存在错误「$expected」，实际错误:\n${errorMessages()}")
    return error!!
}

/** 断言至少有一条告警匹配 key；返回第一条匹配项。 */
fun AnalysisResult.assertHasWarning(key: String, vararg args: Any): Diagnostic {
    val expected = format(key, args)
    val warning = warnings.firstOrNull { it.message == expected }
    assertTrue(warning != null, "应存在告警「$expected」，实际告警:\n${warningMessages()}")
    return warning!!
}

/** 按顺序断言各 label 覆盖的源码原文。 */
fun Diagnostic.assertLabelSourceTexts(source: String, vararg expected: String) {
    assertEquals(expected.size, labels.size, "label 数量不符")
    for ((index, text) in expected.withIndex()) {
        val span = labels[index].span
        assertEquals(text, source.substring(span.start, span.end), "第 ${index + 1} 个 label 的源码片段")
    }
}

fun AnalysisResult.errorMessages(): String = errors.joinToString("\n") { it.message }

fun AnalysisResult.warningMessages(): String = warnings.joinToString("\n") { it.message }

private fun format(key: String, args: Array<out Any>): String =
    if (args.isEmpty()) I18N.bundle.get(key) else I18N.bundle.format(key, *args)
