# AGENTS — Guidance for automated coding agents

Purpose: give an AI code-writing or code-understanding agent the minimal, actionable knowledge to be immediately
productive in this repository.

Quick start (Windows / PowerShell)
- Build & run the CLI compile task (recommended):
    - .\gradlew.bat compile # runs mlogix.Main with arg 'c'
    - .\gradlew.bat compile-debug# runs mlogix.Main with args 'c' 'd' (extra debug flag)
  - 快速试错：需要测试时直接修改项目根目录下的 `../../MLogiX/test.mlx`（无需保留原内容），然后运行
    `.\gradlew.bat compile` 即可在终端看到词法/语法/类型推断等报错。`Compiler.compile()` 会遍历 项目下所有 `.mlx` 文件（
    `../../MLogiX/src/mlogix/compiler/Compiler.kt`），无需手动指定文件。
- Run unit tests:
    - .\gradlew.bat test
    - Run a single test class: .\gradlew.bat test --tests "mlogix.compiler.LexerTest"
- JAR output: build/libs/MLogiX.jar (Gradle produces this artifact)

Big picture architecture

- This repo is a small language front-end (lexer → parser → AST → semantic analysis → problem reporting).
- Runtime/entrypoint: `../../MLogiX/src/mlogix/Main.java` — interprets CLI args and calls `Compiler`.
- Compiler orchestration: `../../MLogiX/src/mlogix/compiler/Compiler.kt` — creates `Lexer`, `Parser`, `DiagManager`,
  `SourceMapManager`, builds a `CompilationPipeline` and runs per-file phases. Treat it as the canonical pipeline when
  adding features.
- Pass pipeline: `../../MLogiX/src/mlogix/compiler/pipeline/CompilationPipeline.kt` runs an ordered list of `CompilerPass` (contract
  in `../../MLogiX/src/mlogix/compiler/core/pass/CompilerPass.kt`, ids in `PassId.kt`). Passes communicate via IR data and a shared
  `CompilerContext` (`../../MLogiX/src/mlogix/compiler/core/CompilerContext.kt`, concrete `pipeline/CompilationContext.kt`). Add new
  passes to the `Seq` in `Compiler.compile()`. The pipeline entry is per-file: `pipeline.run(sourceMap, context)` (input
  `SourceMap`, output `Stmt`).
- Pass ids: `PARSE`, `RESOLUTION`, `DESUGAR`, `TYPE_INFERENCE`, `DATAFLOW`, `EXHAUSTIVENESS`
  (`../../MLogiX/src/mlogix/compiler/core/pass/PassId.kt`).
- Lexing + parsing (one pass): `../../MLogiX/src/mlogix/compiler/passes/parsing` — `Lexer.kt` tokenizes input into `Token` objects
  with `Span` locations; `Parser.kt` builds `Expr`/`Stmt` nodes; `ParsingPass.kt` wraps both as
  `CompilerPass<SourceMap, Stmt>`. **关键设计：Parser 持有 Lexer，通过前瞻缓冲按需实时调用 `Lexer.scanToken()`——绝不预先生成完整
  token 列表再交给 Parser**（避免中间 token 数组的性能损失）。错误恢复/回溯依赖 Lexer 快照（
  `Lexer.createSnapshot/restoreSnapshot`）。
- Semantic analysis / type inference: `../../MLogiX/src/mlogix/compiler/passes/typing` — `TypeInferencer` (constraint generation +
  lazy solving via `TypeSolver`, `Constraint`, `InferResult`), wrapped as `TypeInferencePass`.
- Type system: `../../MLogiX/src/mlogix/compiler/core/type` — **sealed 代数结构** `Type`（`Con`/`Var`/`Func`/`Arr`/`TupleType`/
  `Unknown`/`Error`），结构相等用 `==`；`TypeVar` 是 `Type.Var(index: Int)`，并查集按 Int 索引（`arc.struct.IntMap`）；
  `TypeScheme`（∀ 多态）含 `instantiate/generalize/freeTypeVars`；`TypeVisitor` 是统一遍历器（occurs check / 自由变量收集都用它）。类型系统
  **绝不 throw**，出错注入 `Type.Error` 抑制级联错误。
- Future passes (directories already scaffolded, currently empty): `passes/resolution/`, `passes/desugar/`,
  `passes/dataflow/`, `passes/exhaustiveness/`, `passes/borrowck/`.
- AST: `../../MLogiX/src/mlogix/compiler/ast` — `Expr`/`Stmt` nodes. `ASTPrinter.kt` is used for debugging/printing ASTs.
- Source mapping & errors: `../../MLogiX/src/mlogix/compiler/core/SourceMapManager.kt`, `../../MLogiX/src/mlogix/compiler/diagnostic/Diagnostic.kt`,
  `DiagManager.kt` — errors and warnings are associated with `Span` and printed with contextual lines.

Project-specific conventions and important patterns

- Single-source layout: both Java and Kotlin sources live under `../../MLogiX/src` (Gradle configured to use `src` for java and
  kotlin). Tests live under `../../MLogiX/test`.
- 不使用不明确的缩写，比如：在不是简短for循环或lambda的形参时不允许变量为单词首字母或多个单词首字母直接结合 (如 `cond` ->
  `c`, `symbol` -> `sym`/`s`, `constrait` -> `cst`/`c`, `index` -> `idx`/`i`等都是不允许的)
    - 允许的缩写：
        - `ctx` → `context`，`src` → `source`，`stmt` → `statement`，`expr` → `expression`
        - `fn` → `function`，`var` → `variable`，`res` → `result`
        - `l` → `left`，`r` → `right`但注意必须明显，作为变量时先写小写`l`/`r`，而后跟上开头大写的变量名
- Library usage constraints (库调用限制) — 必须遵守:
    - **尽量不使用 java.util 集合**（`ArrayList`/`List`/`HashMap`/`Map`/`LinkedList`…），尽量使用 `arc.struct`：
        - 列表 → `arc.struct.Seq<T>`；映射 → `arc.struct.ObjectMap<K,V>` / `ArrayMap<K,V>`；队列 → `arc.struct.Queue<T>`
          ；原始类型序列 → `arc.struct.IntSeq` / `FloatSeq` / `LongSeq`。
    - **陷阱**：
        - Arc 的 `ArrayMap.values` 运行时是 `Object[]` 泛型数组，直接对其做类型化数组操作（如 `values.sum()`）会抛
          `ClassCastException`。请用 `forEach` 遍历累加（参见 `Compiler.PhaseTimer.printPhaseTimes()` 的修复注释）。
        - `arc.struct.EnumSet`是基于int的，所以其最多支持32个枚举值，涉及枚举值集合时一律使用`java.util.EnumSet`
        - `arc.struct.Seq` 在构造时不填参数会默认分配大小为16，构造空`Seq`务必注意使用`Seq(0)`来构造
    - 文件 IO 用 `arc.files.Fi` 抽象（见 `Main.java` / `SourceMapManager.kt`）。
    - 函数式接口用 `arc.func.Cons` / `Prov` / `Boolf`；`java.util.function.Consumer` 仅用于与标准库对接的解耦场景（如
      `ProblemCollector.printError()`）。
    - 日志用 `arc.util.Log`（见 `mlogix/util/Log.kt`、`mlogix/util/Ansi.kt`）。
    - 颜色字面量（`0%RRGGBB` / `0%colorName`）由 Lexer 转为 `arc.graphics.Color` 的 double-bits（`Color.toDoubleBits`）。
  - `Seq`使用须知:
      - 不带参数的`Seq`默认初始化16个位置，当能够预测数据数量甚至确定数据数量为0时（比如函数参数），填入预估的初始大小参数，不要往小估。
      - 明确该Seq不再更改时，使用`shrink()`方法压缩占用。
      - 批量添加数据使用`addAll`。
      - 批量修改数据并返回新`Seq`使用`map()`方法。
- Language positions: use `Span` (in `../../MLogiX/src/mlogix/compiler/core/span/Span.kt`) across AST nodes and problems. When
  changing AST nodes, ensure spans are correct (use `Span.between` or propagate `token.span`).
- Problem reporting: create `Diagnostic` instances with a `SourceMap` and then call `ProblemCollector.printError()`; the
  collector is the single place tests and the Compiler inspect for failure counts.
- Passes must never reference each other directly; they exchange data through IR and share state via `CompilerContext`
  (open-closed principle, see the layered dependency direction in `../../MLogiX/plan.md`).
- Tests use JUnit 5 (see `../../MLogiX/build.gradle` test configuration). Test examples:
    - `../../MLogiX/test/mlogix/compiler/LexerTest.kt` shows how to instantiate `Lexer` and assert token sequences.
    - `../../MLogiX/test/mlogix/compiler/ParserTest.kt` shows parser usage: `parser.parse("2 + 3")`.

使用类似rust的面向对象系统

Integration points & external dependencies

- Dependencies declared in `../../MLogiX/build.gradle`: arc-core and Mindustry core from custom Maven endpoints. The project pins a
  `mindustryVersion` and enforces Arc versions via a resolution strategy — when modifying dependencies preserve this
  pattern (see lines 35–59 in `../../MLogiX/build.gradle`).
- Gradle toolchain: Java 17 and Kotlin JVM toolchain set in `../../MLogiX/build.gradle` — CI or agents must target JDK 17.

Developer workflows important to automation
- Adding sources: place new Kotlin/Java files under `src/mlogix/…` (packages are `mlogix`), tests under `test/mlogix/…`.
- Running the project in-process: use Gradle JavaExec tasks `compile` and `compile-debug` (these are the intended
  execution shortcuts instead of a `run` task).
- Test artifacts & results are in `../../MLogiX/build/test-results` and `../../MLogiX/build/reports/tests` — CI agents should inspect these for
  failures.

Recommended extension tasks for agents (when implementing features)

- When adding a new token type: update `Token` definitions, `Lexer.scanToken()` in `Lexer.kt`, update `Parser` to
  consume it, and add unit tests in `test/mlogix/compiler/*`.
- When adding AST node types: update `compiler/ast/*.kt`, `ASTPrinter.kt` for debug printing, and adjust parser (s) in
  `Parser.kt` to create the node. Ensure spans are assigned.

Where to read language behaviour and design rationale
- User-facing grammar and quick guides: `docs/grammar/index.md` and `docs/grammar/fast-learning.md`.
- Semantic analysis design (constraint generation + lazy solving): `docs/design/semantic-analyzer.md`.
- Design notes / parser recovery: `../../MLogiX/src/mlogix/compiler/TODO-scope_stack.md` — explains error recovery rationale used by
  the parser.
- High-level roadmap and component breakdown: `../../MLogiX/plan.md`.

Searchable anchors for an agent
- Entrypoint: `../../MLogiX/src/mlogix/Main.java`
- Compiler orchestration: `../../MLogiX/src/mlogix/compiler/Compiler.kt`
- Pass pipeline: `../../MLogiX/src/mlogix/compiler/pipeline/CompilationPipeline.kt`
- Pass contract: `../../MLogiX/src/mlogix/compiler/core/pass/CompilerPass.kt`, `../../MLogiX/src/mlogix/compiler/core/pass/PassId.kt`
- Compiler context: `../../MLogiX/src/mlogix/compiler/core/CompilerContext.kt`, `../../MLogiX/src/mlogix/compiler/pipeline/CompilationContext.kt`
- Parsing pass: `../../MLogiX/src/mlogix/compiler/passes/parsing/ParsingPass.kt`
- Type inference: `src/mlogix/compiler/passes/typing/*.kt`
- Lexer: `../../MLogiX/src/mlogix/compiler/passes/parsing/Lexer.kt`
- Parser: `../../MLogiX/src/mlogix/compiler/passes/parsing/Parser.kt`
- AST: `src/mlogix/compiler/ast/*.kt`
- Problems: `src/mlogix/compiler/diagnostic/*.kt`

If you need more context

- Prefer reading `Compiler.kt` first to see how phases are wired. Then read `SourceMapManager.kt`, `DiagManager.kt`
  and tests to understand expected behavior and error formats.

License & docs: see `../../MLogiX/README.md` and `docs/` for language-user docs.
