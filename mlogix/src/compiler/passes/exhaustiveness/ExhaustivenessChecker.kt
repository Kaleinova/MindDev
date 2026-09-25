package mlogix.compiler.passes.exhaustiveness

import arc.struct.ObjectSet
import arc.struct.Seq
import mlogix.compiler.ast.Expr
import mlogix.compiler.ast.Pattern
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.span.Span
import mlogix.compiler.core.type.Type
import mlogix.compiler.diagnostic.Diagnostic
import mlogix.compiler.diagnostic.Diagnostic.SemanticDiag
import mlogix.util.I18N.bundle

/**
 * 枚举变体的构造器信息（穷尽性检查用）：变体名 + 已按被匹配类型实参代入的载荷类型。
 */
class VariantInfo(val name: String, val argTypes: Seq<Type>)

/**
 * 一个 `match` 的覆盖性检查输入（由 TypeInferencer 在**求解之后**构造：
 * 类型推断是目前唯一持有已求解类型的地方）。
 *
 * @param matchSpan 整个 match 语句的位置（note 的 label）
 * @param scrutineeSpan 被匹配表达式的位置（主 label）
 * @param scrutineeType 被匹配值类型（**walk 阶段的原始类型**，可能还是未求解的变量）
 * @param arms 各分支的模式（按书写顺序）
 * @param variantsOf 类型的构造器集合：枚举返回其变体，非枚举返回 null（无法做穷尽判定）
 * @param solve 把类型归一化到求解结果（即 `TypeSolver.read`）：检查发生在求解之后，
 *   但这里保存的是 walk 阶段的类型变量，必须先读回具体类型才能取到枚举名与变体表
 */
class MatchCoverage(
    val matchSpan: Span,
    val scrutineeSpan: Span,
    val scrutineeType: Type,
    val arms: Seq<Pattern>,
    val variantsOf: (Type) -> Seq<VariantInfo>?,
    val solve: (Type) -> Type,
)

/**
 * 穷尽性与冗余检查（Maranget 模式矩阵 usefulness 算法，对齐 rustc 的 E0004 与 unreachable_patterns）。
 *
 * 本语言当前模式语言很小（通配符 / 绑定 / 枚举变体，无字面量与 or 模式），因此算法可以写得紧凑：
 * - **构造器完备**只对「构造器集合已知」的类型（枚举）成立；整数/字符串等构造器未知 → 永远不完备
 *   → 只有 `_`/绑定分支才能穷尽；
 * - **冗余**：某分支相对前面所有分支不可达 → 警告；
 * - **穷尽性**：整体仍有未覆盖的取值 → 错误，并在消息里给出**反例**（如 `Option.Some(_)`）。
 *
 * 前置条件：[check] 假定每个分支的模式都匹配被匹配类型的取值。一旦有分支写了**别的枚举**的
 * 变体（类型检查已报错），可达性/穷尽性判定整体放弃——错误的模式会同时污染两侧结论
 * （把正常分支误报成不可达、或漏报穷尽性），叠加在同一条已报错误上没有增益。
 *
 * [MatchCoverage.variantsOf] 把符号表/求解器挡在外面，检查器可单独测试。
 */
class ExhaustivenessChecker(private val context: CompilerContext) {

    /** 归一化模式：算法只关心「任意值」与「哪个构造器 + 子模式」，不关心源码位置 */
    private sealed class Pat {
        data object Any : Pat()

        /** @param enumName 便于把反例渲染成 `枚举名.变体` */
        data class Ctor(val enumName: String, val name: String, val args: Seq<Pat>) : Pat()
    }

    /**
     * 检查一个 match：绑定名撞变体名告警、不可达分支告警、缺失分支报错。
     */
    fun check(coverage: MatchCoverage) {
        // 求解后的被匹配类型：枚举名、变体表、消息都基于它
        val scrutineeType = coverage.solve(coverage.scrutineeType)
        val scrutineeVariants = coverage.variantsOf(scrutineeType)

        // 绑定名撞变体名：与穷尽性判定无关，独立遍历（每个位置都需要该位置的类型）
        for (pattern in coverage.arms) {
            warnBindingsNamedLikeVariant(pattern, scrutineeType, coverage.variantsOf)
        }

        // 被匹配类型不是可判定的枚举（未求解/整数/字符串…）：不做穷尽性与可达性判定
        if (scrutineeVariants == null) return

        // 分支里写了**别的枚举**的变体（`match c { Other.Blue -> }`，`c: Color`）：类型检查
        // 已经就地报过「类型不匹配」并给出修复方向，这里**整体放弃**可达性/穷尽性判定。
        // 覆盖性分析的前提是每个模式都是本枚举的取值之一，错误模式破坏该前提后会反过来
        // 污染**正常**分支的结论，且方向随写法而变：
        // - 错误模式写在正常分支前面时，它经由 default 矩阵把自己算成「覆盖了一个取值」，
        //   把正常分支误报成「不可达」；
        // - 错误模式写在后面时，它又挡掉「本枚举还有取值没匹配」的结论，漏报穷尽性错误。
        // 两者都只是加剧同一条已报错误的噪声，故一律不报：等用户修好类型，分析自然成立。
        if (coverage.arms.any { mismatchedVariantPattern(it, scrutineeType, coverage.variantsOf) }) return

        val rows = Seq<Seq<Pat>>(coverage.arms.size)
        for (pattern in coverage.arms) rows.add(Seq.with(normalize(pattern, scrutineeType, coverage.variantsOf)))

        // 1) 冗余：某分支相对它前面的分支不可达 → 警告
        for ((i, pattern) in coverage.arms.withIndex()) {
            val previous = Seq<Seq<Pat>>(i)
            for (j in 0 until i) previous.add(rows.get(j))
            val usefulResult = useful(previous, rows.get(i), Seq.with(scrutineeType), coverage.variantsOf)
            if (usefulResult == null) {
                warning(bundle.get("diag.unreachable-arm"))
                    .label(pattern, bundle.get("diag.unreachable-arm.help"))
            }
        }

        // 2) 穷尽性：整体仍有未覆盖的取值 → 错误（消息里给出反例）
        val missing = useful(rows, Seq.with(Pat.Any), Seq.with(scrutineeType), coverage.variantsOf)
        if (missing != null) {
            val witness = render(missing.first())
            error(bundle.format("diag.non-exhaustive-match", witness))
                .label(coverage.scrutineeSpan, bundle.format("diag.non-exhaustive-match.help", witness))
                .note(
                    bundle.format(
                        "diag.non-exhaustive-match.note",
                        scrutineeType.pretty(),
                        scrutineeVariants.joinToString(", ") { it.name },
                    )
                )
                .label(coverage.matchSpan, "")
        }
    }

    // ========== 绑定名撞变体名 ==========

    /**
     * 绑定名与被匹配枚举的变体同名时告警：本语言的裸标识符一律是**绑定**，
     * 写 `None` 不会匹配变体 `None`，而是绑定一个叫 `None` 的新变量（rustc 的经典坑）。
     */
    private fun warnBindingsNamedLikeVariant(
        pattern: Pattern,
        expected: Type,
        variantsOf: (Type) -> Seq<VariantInfo>?,
    ) {
        when (pattern) {
            is Pattern.Wildcard -> Unit

            is Pattern.Binding -> {
                val name = identifierName(pattern.name) ?: return
                val variants = variantsOf(expected) ?: return
                if (variants.any { it.name == name }) {
                    warning(bundle.format("diag.binding-named-like-variant", name, typeNameOf(expected) ?: "?"))
                        .label(pattern, bundle.get("diag.binding-named-like-variant.help"))
                }
            }

            is Pattern.Variant -> {
                val name = identifierName(pattern.path.field) ?: return
                val variant = variantsOf(expected)?.firstOrNull { it.name == name }
                for ((i, arg) in pattern.args.withIndex()) {
                    if (variant != null && i < variant.argTypes.size) {
                        warnBindingsNamedLikeVariant(arg, variant.argTypes.get(i), variantsOf)
                    }
                }
            }
        }
    }

    // ========== 模式矩阵 usefulness ==========

    /**
     * [q]（若干列的模式）是否匹配到 [matrix] 未覆盖的取值？
     *
     * @return 反例模式序列；`null` 表示已被 matrix 完全覆盖（[q] 不可达）
     */
    private fun useful(
        matrix: Seq<Seq<Pat>>,
        q: Seq<Pat>,
        types: Seq<Type>,
        variantsOf: (Type) -> Seq<VariantInfo>?,
    ): Seq<Pat>? {
        if (types.isEmpty) return if (matrix.isEmpty) Seq(0) else null

        val head = q.first()
        val type = types.first()
        val restTypes = tailOf(types, 1)

        // 首列是构造器模式：特化后递归，再把载荷反例包回构造器
        if (head is Pat.Ctor) {
            // 模式写的是别的枚举的变体（类型检查已报错）：它匹配不到本列类型的任何取值 → 不可达
            if (!ctorBelongsTo(head, type, variantsOf)) return null
            val info = variantsOf(type)?.firstOrNull { it.name == head.name } ?: return null
            val specialized = specialize(matrix, head.name, info.argTypes.size, type, variantsOf)
            val witness =
                useful(specialized, concat(head.args, tailOf(q, 1)), concat(info.argTypes, restTypes), variantsOf)
                    ?: return null
            return concat(
                Seq.with(Pat.Ctor(head.enumName, head.name, takePat(witness, info.argTypes.size))),
                tailOf(witness, info.argTypes.size)
            )
        }

        // 首列是通配符/绑定（Pat.Any）
        val variants = variantsOf(type)
        val seen = ObjectSet<String>()
        for (row in matrix) {
            val first = row.first()
            // 只统计**本枚举**的变体：别的枚举的变体即使同名也不覆盖本类型的取值
            // （否则 `Color.Red` + `Other.Blue` 会被当成覆盖了 `Color` 的两个变体）
            if (first is Pat.Ctor && ctorBelongsTo(first, type, variantsOf)) seen.add(first.name)
        }

        // 构造器完备（枚举的全部变体都出现过）：逐个特化递归，任一给出反例即可
        if (variants != null && variants.all { seen.contains(it.name) }) {
            for (info in variants) {
                val specialized = specialize(matrix, info.name, info.argTypes.size, type, variantsOf)
                val wildcards = Seq<Pat>(info.argTypes.size)
                repeat(info.argTypes.size) { wildcards.add(Pat.Any) }
                val witness =
                    useful(specialized, concat(wildcards, tailOf(q, 1)), concat(info.argTypes, restTypes), variantsOf)
                        ?: continue
                val enumName = typeNameOf(type) ?: info.name
                return concat(
                    Seq.with(Pat.Ctor(enumName, info.name, takePat(witness, info.argTypes.size))),
                    tailOf(witness, info.argTypes.size),
                )
            }
            return null
        }

        // 构造器不完备：走 default 矩阵（首列是通配符的行），并挑一个缺失构造器作为反例
        val default = Seq<Seq<Pat>>(matrix.size)
        for (row in matrix) {
            if (row.first() is Pat.Any) default.add(tailOf(row, 1))
        }
        val witness = useful(default, tailOf(q, 1), restTypes, variantsOf) ?: return null
        val missing = variants?.firstOrNull { !seen.contains(it.name) }
        if (missing == null) return concat(Seq.with(Pat.Any), witness)
        val args = Seq<Pat>(missing.argTypes.size)
        repeat(missing.argTypes.size) { args.add(Pat.Any) }
        return concat(Seq.with(Pat.Ctor(typeNameOf(type) ?: missing.name, missing.name, args)), witness)
    }

    /**
     * 只保留首列匹配构造器 [name] 的行，并把载荷列展开到首列之前
     * （通配符行展开成 [arity] 个任意值）。
     *
     * [type] 用于排除「写了别的枚举的变体」的行：那种模式永远匹配不到本列的取值，
     * 因此既不进入特化矩阵，也不能算作覆盖。
     */
    private fun specialize(
        matrix: Seq<Seq<Pat>>,
        name: String,
        arity: Int,
        type: Type,
        variantsOf: (Type) -> Seq<VariantInfo>?,
    ): Seq<Seq<Pat>> {
        val result = Seq<Seq<Pat>>(matrix.size)
        for (row in matrix) {
            when (val first = row.first()) {
                is Pat.Any -> {
                    val expanded = Seq<Pat>(arity + row.size)
                    repeat(arity) { expanded.add(Pat.Any) }
                    expanded.addAll(tailOf(row, 1))
                    result.add(expanded)
                }

                is Pat.Ctor -> if (first.name == name && ctorBelongsTo(first, type, variantsOf)) {
                    result.add(concat(first.args, tailOf(row, 1)))
                }
            }
        }
        return result
    }

    // ========== 小工具（arc Seq 没有 Kotlin 那样的 drop/plus） ==========

    private fun <T> tailOf(seq: Seq<T>, from: Int): Seq<T> {
        val result = Seq<T>(maxOf(seq.size - from, 0))
        for (i in from until seq.size) result.add(seq.get(i))
        return result
    }

    private fun <T> concat(a: Seq<T>, b: Seq<T>): Seq<T> {
        val result = Seq<T>(a.size + b.size)
        result.addAll(a)
        result.addAll(b)
        return result
    }

    private fun takePat(seq: Seq<Pat>, count: Int): Seq<Pat> {
        val result = Seq<Pat>(count)
        for (i in 0 until count) {
            if (i >= seq.size) break
            result.add(seq.get(i))
        }
        return result
    }

    // ========== 归一化与渲染 ==========

    /**
     * 归一化模式：算法只关心「任意值」与「哪个构造器 + 子模式」，不关心源码位置。
     *
     * 关键是**按变体声明的载荷数量补齐/截断**：载荷数量写错（类型检查已报错）时，
     * 模式矩阵的「每行列数一致」不变量会被破坏，补齐成 `_` 既保持不变量，
     * 也避免在已经报错的代码上再叠一条穷尽性错误。
     */
    private fun normalize(pattern: Pattern, expected: Type, variantsOf: (Type) -> Seq<VariantInfo>?): Pat =
        when (pattern) {
            is Pattern.Wildcard -> Pat.Any
            is Pattern.Binding -> Pat.Any

            is Pattern.Variant -> {
                val enumName = identifierName(pattern.path.obj)
                val variantName = identifierName(pattern.path.field) ?: "?"
                val info = variantsOf(expected)?.firstOrNull { it.name == variantName }
                val argTypes = info?.argTypes ?: Seq(0)
                val arity = if (info != null) argTypes.size else pattern.args.size

                val args = Seq<Pat>(arity)
                for (i in 0 until arity) {
                    val sub = if (i < pattern.args.size) pattern.args.get(i) else null
                    val subType = if (i < argTypes.size) argTypes.get(i) else expected
                    args.add(if (sub == null) Pat.Any else normalize(sub, subType, variantsOf))
                }
                Pat.Ctor(enumName ?: variantName, variantName, args)
            }
        }

    /** 反例 → 可读文本（`Option.Some(_)`）；任意值渲染成 `_` */
    private fun render(pat: Pat): String = when (pat) {
        is Pat.Any -> "_"
        is Pat.Ctor ->
            if (pat.args.isEmpty) {
                "${pat.enumName}.${pat.name}"
            } else {
                "${pat.enumName}.${pat.name}(${pat.args.joinToString(", ") { render(it) }})"
            }
    }

    private fun identifierName(expr: Expr?): String? {
        val identifier = expr as? Expr.Identifier ?: return null
        return (identifier.token.literal as? String) ?: identifier.token.type.toString()
    }

    /**
     * [ctor] 是否是 [type]（本列的被匹配类型）**自己**的变体？
     *
     * 模式里的 `枚举名.变体` 由类型检查保证与被匹配类型一致；不一致时（错误模式）穷尽性检查
     * 必须把它当作「匹配不到任何取值」而不是「覆盖了同名变体」，否则会：
     * - 误报不可达告警（`Other.Blue` 被当成覆盖了 `Color.Blue`）；
     * - 漏报穷尽性错误（同名变体把本枚举的变体算作已覆盖）。
     *
     * 这类错误模式还会**反向**污染别的分支的结论（把正常分支误报成不可达 / 漏报穷尽性），
     * 因此 [check] 在分析前先用 [mismatchedVariantPattern] 识别它们并整体放弃判定。
     *
     * 模式上没写出枚举名（取不到）时不做否定判断，退回按变体名比较的旧行为。
     */
    private fun ctorBelongsTo(ctor: Pat.Ctor, type: Type, variantsOf: (Type) -> Seq<VariantInfo>?): Boolean {
        val expectedName = typeNameOf(type)
        if (expectedName != null && ctor.enumName != expectedName) return false
        return variantsOf(type)?.any { it.name == ctor.name } == true
    }

    /**
     * [pattern] 是否写了**别的枚举**的变体（即该模式匹配不到 [expected] 的任何取值）？
     *
     * 与 [ctorBelongsTo] 同一口径、但作用于「源码模式」而非归一化后的 [Pat]：穷尽性判定要能在
     * 归一化**之前**识别出错误模式并整体退出。判定必须同时看**枚举名**与**变体名**：
     * 只看变体名的话，`Color { Red, Blue }` 里的 `Other.Blue` 会因为「本枚举恰好也有 Blue」
     * 而被当成合法模式，正是这条误报的根源。
     *
     * 递归载荷：`Option.Some(Other.Blue)` 的错在里层，外层看着没错。
     * 口径是「构造器不属于 [expected] 这个类型」，因此也覆盖「模式写在非枚举类型上」
     * （`match n { Option.None -> }`）——那种模式同样匹配不到任何取值。
     */
    private fun mismatchedVariantPattern(
        pattern: Pattern,
        expected: Type,
        variantsOf: (Type) -> Seq<VariantInfo>?,
    ): Boolean = when (pattern) {
        is Pattern.Wildcard -> false

        // 裸标识符一律是**绑定**（匹配所有取值，normalize 也把它当 Pat.Any）：
        // 即使与变体同名，类型检查也只是告警而不是报错，不属于「错误模式」。
        is Pattern.Binding -> false

        is Pattern.Variant -> {
            val variants = variantsOf(expected)
            val variantName = identifierName(pattern.path.field)
            val info = variants?.firstOrNull { it.name == variantName }
            val writtenEnumName = identifierName(pattern.path.obj)
            val expectedEnumName = typeNameOf(expected)
            when {
                // 期望类型不是枚举（`match n { Option.None -> }`，`n: Int`）：构造器不属于它
                variants == null -> true
                // 路径上写的枚举名不是被匹配的枚举（`Other.Blue` 对 `Color`）
                expectedEnumName != null && writtenEnumName != null && writtenEnumName != expectedEnumName -> true
                // 本枚举没有这个变体名：枚举名写错（Resolver 已报错）
                info == null -> true
                // 载荷数量写错：类型检查已报错，无法定位到出错的那一位 → 不作穷尽性判定
                pattern.args.size != info.argTypes.size -> true
                else -> pattern.args.withIndex().any { (i, arg) ->
                    mismatchedVariantPattern(arg, info.argTypes.get(i), variantsOf)
                }
            }
        }
    }

    /** 具名类型的名字（枚举名）：`App(Con(N), args)` / `Con(N)` */
    private fun typeNameOf(type: Type): String? = when (type) {
        is Type.App -> type.con.name
        is Type.Con -> type.name
        else -> null
    }

    // ========== 诊断 ==========

    private fun error(text: String): SemanticDiag {
        val e = SemanticDiag(text, Diagnostic.DiagLevel.ERROR)
        context.diagHandler.addError(e)
        return e
    }

    private fun warning(text: String): SemanticDiag {
        val w = SemanticDiag(text, Diagnostic.DiagLevel.WARNING)
        context.diagHandler.addWarning(w)
        return w
    }
}
