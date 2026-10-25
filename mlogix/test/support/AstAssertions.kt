package mlogix.compiler.support

import arc.struct.Seq
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.token.Token
import org.junit.jupiter.api.Assertions.assertEquals
import java.lang.reflect.Modifier

/**
 * AST 形状断言。
 *
 * Span.equals 按 bits 比较；但 Parser/AST 测试里的期望树是用 `Span(0, 0, 0)`
 * 拼出来的，只想验证「树形结构 + token 内容」，不关心每个节点的真实位置。
 * 因此这里提供一个**测试专用**的比较器：忽略 Span，Token 按 type/literal 比较，
 * 其余字段递归比较。真实 span 由 SpanTest 与诊断定位测试覆盖。
 */
fun assertAstEquals(expected: Any?, actual: Any?, message: String = "AST 结构必须匹配") {
    if (!astEquals(expected, actual)) {
        assertEquals(expected, actual, message)
    }
}

private fun astEquals(expected: Any?, actual: Any?): Boolean {
    if (expected === actual) return true
    if (expected == null || actual == null) return false

    if (expected is Span || actual is Span) {
        return expected is Span && actual is Span
    }
    if (expected is Token || actual is Token) {
        return expected is Token && actual is Token && expected == actual
    }
    if (expected is Seq<*> || actual is Seq<*>) {
        if (expected !is Seq<*> || actual !is Seq<*>) return false
        if (expected.size != actual.size) return false
        for (index in 0 until expected.size) {
            if (!astEquals(expected.get(index), actual.get(index))) return false
        }
        return true
    }
    if (expected is Array<*> || actual is Array<*>) {
        if (expected !is Array<*> || actual !is Array<*>) return false
        if (expected.size != actual.size) return false
        for (index in expected.indices) {
            if (!astEquals(expected[index], actual[index])) return false
        }
        return true
    }
    if (expected.javaClass != actual.javaClass) return false
    if (expected is String || expected is Number || expected is Boolean || expected is Char || expected.javaClass.isEnum) {
        return expected == actual
    }

    val fields = expected.javaClass.declaredFields.filter { field ->
        !field.isSynthetic && !Modifier.isStatic(field.modifiers) && field.name != "span"
    }
    for (field in fields) {
        field.isAccessible = true
        if (!astEquals(field.get(expected), field.get(actual))) return false
    }
    return true
}
