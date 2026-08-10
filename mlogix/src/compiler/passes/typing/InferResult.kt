package mlogix.compiler.passes.typing

import arc.struct.Seq
import mlogix.compiler.core.type.Type

data class InferResult(val type: Type, val constraints: Seq<Constraint>)


