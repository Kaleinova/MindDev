package mlogix.compiler.passes.typing

import arc.struct.Seq
import mlogix.compiler.core.SourceFile
import mlogix.compiler.core.type.BuiltinType
import mlogix.compiler.core.type.Type
import mlogix.compiler.support.CompilerTestBase
import mlogix.util.I18N
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * TypeSolver 的合一 / occurs check / 错误类型抑制。
 *
 * 这些是类型推断的「算术」；先在这里用最小类型树测准，端到端用例才只需要关心
 * 语法与约束生成。
 */
class TypeSolverTest : CompilerTestBase() {

    private fun solver(): TypeSolver = TypeSolver(diagHandler, SourceFile(""))

    @Test
    fun `freshVar increments from zero`() {
        val solver = solver()
        assertEquals(Type.Var(0), solver.freshVar())
        assertEquals(Type.Var(1), solver.freshVar())
    }

    @Test
    fun `bind and read follow the union find root`() {
        val solver = solver()
        solver.bind(0, Type.Con("Int"))

        assertEquals(Type.Con("Int"), solver.read(Type.Var(0)))
        assertEquals(Type.Func(Seq.with(Type.Con("Int")), Type.Con("Int")),
            solver.normalize(Type.Func(Seq.with(Type.Var(0)), Type.Var(0))))
    }

    @Test
    fun `solveEqualities descends into function params and result`() {
        val solver = solver()
        val variable = solver.freshVar()

        solver.solveEqualities(
            Seq.with(
                Constraint.Equal(
                    Type.Func(Seq.with(variable), variable),
                    Type.Func(Seq.with(Type.Con("Int")), Type.Con("Int")),
                )
            )
        )

        assertEquals(Type.Con("Int"), solver.read(variable))
        assertEquals(0, diagHandler.errorNum())
    }

    @Test
    fun `occurs check reports instead of creating a recursive type`() {
        val solver = solver()
        val variable = solver.freshVar()

        solver.solveEqualities(
            Seq.with(
                Constraint.Equal(
                    variable,
                    Type.Func(Seq.with(variable), Type.Con("Int")),
                )
            )
        )

        assertEquals(1, diagHandler.errorNum())
        assertEquals(
            I18N.bundle.format("diag.occurs-check", variable.index),
            diagHandler.errors[0].message,
        )
    }

    @Test
    fun `Dummy and Unknown are silent`() {
        val solver = solver()

        solver.solveEqualities(
            Seq.with(
                Constraint.Equal(Type.Con("Int"), Type.Dummy),
                Constraint.Equal(Type.Unknown, Type.Con("Str")),
            )
        )

        assertEquals(0, diagHandler.errorNum())
    }

    @Test
    fun `Arr and App Array are compatible`() {
        val solver = solver()

        solver.solveEqualities(
            Seq.with(
                Constraint.Equal(
                    Type.Arr(Type.Con("Int")),
                    Type.App(BuiltinType.Array, Seq.with(Type.Con("Int"))),
                )
            )
        )

        assertEquals(0, diagHandler.errorNum())
    }

    @Test
    fun `generic constructor names and arity are checked`() {
        val solver = solver()

        solver.solveEqualities(
            Seq.with(
                Constraint.Equal(
                    Type.App(Type.Con("Option"), Seq.with(Type.Con("Int"), Type.Con("Str"))),
                    Type.App(Type.Con("Option"), Seq.with(Type.Con("Int"))),
                )
            )
        )

        assertEquals(1, diagHandler.errorNum())
        assertEquals(
            I18N.bundle.format("diag.type-arg-count", "Option", 2, 1),
            diagHandler.errors[0].message,
        )
    }

    @Test
    fun `concrete mismatches report both types`() {
        val solver = solver()

        solver.solveEqualities(
            Seq.with(Constraint.Equal(Type.Con("Int"), Type.Con("Str")))
        )

        assertEquals(1, diagHandler.errorNum())
        assertEquals(
            I18N.bundle.format("diag.type-mismatch", "Int", "Str"),
            diagHandler.errors[0].message,
        )
    }
}
