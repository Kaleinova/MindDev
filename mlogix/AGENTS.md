# AGENTS — 面向自动化编码 agent 的工作指引

快速开始（Windows / PowerShell）— 所有命令都在**仓库根**执行，见下方「构建前置条件」

- 编译并运行 CLI 编译任务（推荐）：
    - `.\gradlew.bat :mlogix:compile # runs mlogix.Main with arg 'c'`
    - `.\gradlew.bat :mlogix:compile-debug # runs mlogix.Main with args 'c' 'd' (extra debug flag)`
  - 快速试错：需要测试时直接修改 `mlogix/mlogix_test/test.mlx`（无需保留原内容），然后运行
    `.\gradlew.bat :mlogix:compile` 即可在终端看到词法/语法/类型推断等报错。`Compiler.compile()` 会遍历 项目下所有 `.mlx`
    文件（`mlogix/src/compiler/Compiler.kt`），无需手动指定文件。
- 运行单元测试：
    - `.\gradlew.bat :mlogix:test`
    - 只跑单个测试类：`.\gradlew.bat :mlogix:test --tests "mlogix.compiler.LexerTest"`
- JAR 产物：`mlogix/build/libs/mlogix.jar`（由 Gradle 生成）

构建前置条件（先读这一节，能省掉一轮试错）

- **`gradlew.bat` 只在仓库根**（`MindDev/`），在 `mlogix/` 里执行会报「无法将 `.\gradlew.bat` 项识别为 cmdlet…」；
  从子目录跑要用 `..\gradlew.bat`，但这没有必要——留在仓库根最省事（JavaExec 任务的相对路径按仓库根解析，已实测
  `..\gradlew.bat :mlogix:compile` 同样能读到 `mlogix/mlogix_test/test.mlx`）。
- 任务名写 `:mlogix:compile` 是**精确**写法；裸 `compile` 也能用——根工程没有这个任务，Gradle 的任务名匹配会落到
  `:mlogix:compile`（实测可用，两者等效）。**但 `test` 不一样**：裸 `.\gradlew.bat test` 会跑**所有**子工程
  （`:minddev:test` 与 `:mlogix:test` 都会执行），既慢又混入无关失败。因此测试一律写 `:mlogix:test`。
- **构建需要写工作区外的 `~/.gradle`**（守护进程、依赖缓存、wrapper dist）。若 shell 跑在限制写工作区的沙箱里，
  第一次构建会以
  `FileNotFoundException: ...\.gradle\wrapper\dists\...\gradle-9.7.0-bin.zip.lck (拒绝访问)`
  失败——这不是工程问题，也不要换个写法重试：直接以更宽权限（`danger-full-access`）重跑**同一条**命令一次即可。
- **JavaExec 任务（`compile`/`compile-debug`/`tokenize-debug`）的中文诊断默认乱码**：控制台本身是 UTF-8，但 fork 出的 JVM
  用平台默认字符集（中文 Windows 即 GBK）写 stdout，于是 `类型不匹配` 变成 `���Ͳ�ƥ��`。跑这些任务前先设
  `$env:JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"`。Gradle 自身与 `test` 任务的输出不受影响（单测断言走 `I18N.bundle`，
  与字符集无关）。
- 全量 `:mlogix:test` 只需约 10 秒（守护进程热起来后），比逐个类跑更划算；改完直接全量跑，别按类试。
- 诊断断言一律用 `I18N.bundle.get(key)` / `I18N.bundle.format(key, args)`，不要硬编码文案：本机的 CLI 走中文
  `bundle_zh_CN.properties`，而单测里 `I18N.bundle` 初始化为英文 `bundle.properties`，两边文案不同。

文件编码 — 必须遵守

- 所有源码/测试/资源文件一律 **UTF-8 无 BOM**。含中文注释的 Kotlin/Java/`.properties` 文件尤其敏感。
- **绝不用 PowerShell(版本 < 6.0) 重写含中文的源文件。**版本低于6.0的 Windows PowerShell**，其 `Get-Content` / `Set-Content`
  在未显式指定编码时使用 **ANSI（中文系统即 GBK）**。对 UTF-8 文件做一次读/写就会**有损重编码**：多数字节因 GBK
  双向映射幸存，但无法配对成 GBK 的字节会被替换成 `?`（0x3F），文件不再是合法 UTF-8 → IDEA 打开时报
  "该文件以错误的编码加载: 'UTF-8'"，中文注释变乱码，且**不可自动无损还原**。
- 使用Power Shell前先检查Power Shell版本，版本不满足 >= 6.0时必须遵守铁律：
  若确需用 PowerShell 处理文件，读写都显式加 `-Encoding UTF8`（读：`Get-Content -Encoding UTF8`，
  写：`Set-Content -Encoding UTF8`）。

整体架构

> 注意：本文件里的路径都是**仓库根相对**（`mlogix/...`）。源码根是 `mlogix/src/`，包名才是 `mlogix.*`——
> 不存在 `mlogix/src/mlogix/` 这一层（旧文档曾这么写，是按包名臆造的）。

- 本仓库是一个小型语言前端（lexer → parser → AST → 语义分析 → 问题报告）。
- 运行时入口：`mlogix/src/Main.kt` —— 解析 CLI 参数并调用 `Compiler`。
- 编译编排：`mlogix/src/compiler/Compiler.kt` —— 创建 `Lexer`、`Parser`、`DiagHandler`、`SourceMap`，装配一条
  `Pipeline` 并按文件跑各个阶段。新增功能时以它为准（canonical pipeline）。
- Pass 流水线：`mlogix/src/compiler/pipeline/Pipeline.kt` 按顺序执行一串 `CompilerPass`（契约在
  `mlogix/src/compiler/core/pass/CompilerPass.kt`，id 在 `PassId.kt`）。各 pass 之间通过 IR 数据交换、并共享一个
  `CompilerContext`（`mlogix/src/compiler/core/CompilerContext.kt`，具体实现 `pipeline/CompilationContext.kt`）。
  新增 pass 就加到 `Compiler.compile()` 里的那个 `Seq`。流水线入口是**按文件**的：`pipeline.run(sourceFile, context)`
  （输入 `SourceFile`，输出 `Stmt`）。
- Pass id：`PARSE`、`RESOLUTION`、`DESUGAR`、`TYPE_INFERENCE`、`DATAFLOW`、`EXHAUSTIVENESS`
  （`mlogix/src/compiler/core/pass/PassId.kt`）。
- 词法 + 语法（同一个 pass）：`mlogix/src/compiler/passes/parsing` —— `Lexer.kt` 把输入切成带 `Span` 位置的 `Token`；
  `Parser.kt` 构建 `Expr`/`Stmt` 节点；`ParsingPass.kt` 把两者包成 `CompilerPass<SourceMap, Stmt>`。
  **关键设计：Parser 持有 Lexer，通过前瞻缓冲按需实时调用 `Lexer.scanToken()`——绝不预先生成完整
  token 列表再交给 Parser**（避免中间 token 数组的性能损失）。错误恢复/回溯依赖 Lexer 快照（
  `Lexer.createSnapshot/restoreSnapshot`）。
- 语义分析 / 类型推断：`mlogix/src/compiler/passes/typing` —— `TypeInferencer`（约束生成 + 通过 `TypeSolver`、
  `Constraint`、`InferResult` 做惰性求解），包装为 `TypeInferencePass`。
- 类型系统：`mlogix/src/compiler/core/type` —— **sealed 代数结构** `Type`（`Con`/`Var`/`Func`/`Arr`/`TupleType`/
  `Unknown`/`Error`），结构相等用 `==`；`TypeVar` 是 `Type.Var(index: Int)`，并查集按 Int 索引（`arc.struct.IntMap`）；
  `TypeScheme`（∀ 多态）含 `instantiate/generalize/freeTypeVars`；`TypeVisitor` 是统一遍历器（occurs check / 自由变量收集都用它）。类型系统
  **绝不 throw**，出错注入 `Type.Error` 抑制级联错误。
- 尚未实现的 pass（目录已搭好，目前为空）：`passes/resolution/`、`passes/desugar/`、`passes/dataflow/`、
  `passes/exhaustiveness/`、`passes/borrowck/`。
- AST：`mlogix/src/compiler/ast` —— `Expr`/`Stmt` 节点。`ASTPrinter.kt` 用于调试/打印 AST。
- 源码映射与错误：`mlogix/src/compiler/core/SourceMap.kt`、`mlogix/src/compiler/diagnostic/Diagnostic.kt`、
  `mlogix/src/compiler/diagnostic/DiagHandler.kt` —— 错误与告警都关联到 `Span`，打印时带上下文行。

项目约定与重要模式

- 单一源码布局：Java 与 Kotlin 源码都放在 `mlogix/src` 下（Gradle 把 `src` 同时配给 java 与 kotlin）。测试放在
  `mlogix/test` 下。
- 不使用不明确的缩写，比如：在不是简短for循环或lambda的形参时不允许变量为单词首字母或多个单词首字母直接结合 (如 `cond` ->
  `c`, `symbol` -> `sym`/`s`, `constrait` -> `cst`/`c`, `index` -> `idx`/`i`等都是不允许的)
    - 允许的缩写：
        - `ctx` → `context`，`src` → `source`，`stmt` → `statement`，`expr` → `expression`
        - `fn` → `function`，`var` → `variable`，`res` → `result`
        - `l` → `left`，`r` → `right`但注意必须明显，作为变量时先写小写`l`/`r`，而后跟上开头大写的变量名
- 库调用限制 — 必须遵守：
    - **尽量不使用 java.util 集合**（`ArrayList`/`List`/`HashMap`/`Map`/`LinkedList`…），尽量使用 `arc.struct`：
        - 列表 → `arc.struct.Seq<T>`；映射 → `arc.struct.ObjectMap<K,V>` / `ArrayMap<K,V>`；队列 → `arc.struct.Queue<T>`
          ；原始类型序列 → `arc.struct.IntSeq` / `FloatSeq` / `LongSeq`。
    - **陷阱**：
        - Arc 的 `ArrayMap.values` 运行时是 `Object[]` 泛型数组，直接对其做类型化数组操作（如 `values.sum()`）会抛
          `ClassCastException`。请用 `forEach` 遍历累加（参见 `Compiler.PhaseTimer.printPhaseTimes()` 的修复注释）。
        - `arc.struct.EnumSet`是基于int的，所以其最多支持32个枚举值，涉及枚举值集合时一律使用`java.util.EnumSet`
        - `arc.struct.Seq` 在构造时不填参数会默认分配大小为16，构造空`Seq`务必注意使用`Seq(0)`来构造
    - 文件 IO 用 `arc.files.Fi` 抽象（见 `mlogix/src/Main.kt` / `mlogix/src/compiler/core/SourceMap.kt`）。
    - 函数式接口用 `arc.func.Cons` / `Prov` / `Boolf`；`java.util.function.Consumer` 仅用于与标准库对接的解耦场景（如
      `ProblemCollector.printError()`）。
    - 日志用 `arc.util.Log`（见 `mlogix/src/util/Log.java`、`mlogix/src/util/Ansi.kt`）。
    - 颜色字面量（`0%RRGGBB` / `0%colorName`）由 Lexer 转为 `arc.graphics.Color` 的 double-bits（`Color.toDoubleBits`）。
  - `Seq`使用须知:
      - 不带参数的`Seq`默认初始化16个位置，当能够预测数据数量甚至确定数据数量为0时（比如函数参数），填入预估的初始大小参数，不要往小估。
      - 明确该Seq不再更改时，使用`shrink()`方法压缩占用。
      - 批量添加数据使用`addAll`。
      - 批量修改数据并返回新`Seq`使用`map()`方法。
- 语言位置：所有 AST 节点与诊断统一用 `Span`（在 `mlogix/src/compiler/core/span/Span.kt`）。改动 AST 节点时要保证
  span 正确（用 `Span.between`，或直接透传 `token.span`）。
- 问题报告：用 `SourceFile` 构造 `Diagnostic` 实例，再调用 `DiagHandler.addError/addWarning` 收集；`DiagHandler` 是
  测试与 `Compiler` 检查失败计数的唯一入口。
- 各 pass 之间**绝不能互相直接引用**；它们通过 IR 交换数据、通过 `CompilerContext` 共享状态
  （open-closed principle；分层依赖方向见 `mlogix/src/compiler/Compiler.kt` 里 `Seq` 的装配顺序与 `PassId` 的枚举顺序）。
- 测试用 JUnit 5（配置见 `mlogix/build.gradle.kts`）。示例：
    - `mlogix/test/compiler/LexerTest.kt` 展示如何实例化 `Lexer` 并断言 token 序列。
    - `mlogix/test/compiler/ParserTest.kt` 展示 parser 用法：`parser.parse("2 + 3")`。

使用类似rust的面向对象系统

集成点与外部依赖

- 依赖声明在 `mlogix/build.gradle.kts`：arc-core 与 Mindustry core 来自自定义 Maven 端点。工程固定了一个
  `mindustryVersion`，并通过 resolution strategy 约束 Arc 版本——改动依赖时请保留这套模式
  （见 `mlogix/build.gradle.kts` 第 35–59 行）。
- Gradle 工具链：`mlogix/build.gradle.kts` 里设定了 Java 17 与 Kotlin JVM toolchain —— CI 或 agent 必须以 JDK 17 为目标。

对自动化重要的开发工作流
- 新增源码：放在 `mlogix/src/…` 下（包名为 `mlogix`），测试放在 `mlogix/test/…` 下。
- 在进程内运行工程：用 Gradle 的 JavaExec 任务 `compile` 与 `compile-debug`（这是刻意提供的执行快捷方式，
  而不是另建一个 `run` 任务）。
- 测试产物与结果在 `mlogix/build/test-results` 与 `mlogix/build/reports/tests` —— CI agent 应检查这里来看失败原因。

调试诊断/语义问题的推荐手法（先看这节，别用「改一版跑一次」盲猜）

- **短反馈回路**：改 `mlogix/mlogix_test/test.mlx` → `$env:JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"; .\gradlew.bat :mlogix:compile`
  （约 5 秒，打印真实诊断；裸 `compile` 等效，见「构建前置条件」）。要看 AST 用 `:mlogix:compile-debug`，Token 用 `:mlogix:tokenize-debug`。
- **诊断计数/文案断言**：在 `mlogix/test/compiler/*Test.kt` 里加一个临时 `@Test`，复用该文件已有的 `analyze(source)`，
  然后 `println` 出 `context.diagHandler.errors/warnings`：
  ```kotlin
  @Test fun zzTempDump() {
      analyze(""" ... """.trimIndent())
      println("TMP-ERRORS: " + context.diagHandler.errors.joinToString(" | ") { it.message })
      println("TMP-WARNINGS: " + context.diagHandler.warnings.joinToString(" | ") { it.message })
  }
  ```
  跑 `.\gradlew.bat :mlogix:test --tests "mlogix.compiler.MatchTest" --console=plain -i 2>&1 | Select-String "TMP-"`
  即可只看到自己那几行。**临时测试用完必须删掉**，再跑一次全量确认干净。
- **把推断结果打出来再改逻辑**：`Type.pretty()`、`MatchTest` 里的 `analyze`、`Arc` 的 `Seq.joinToString` 足够定位
  「数据到没到这儿」。曾经因为没先确认数据、连续多轮盲改 `useful()`，浪费了 5 次构建；正确做法是**先插桩确认实参到底
  是什么**（例如「枚举名压根没参与比较」），再动手。
- 失败先读 `mlogix/build/test-results/test/TEST-*.xml`（UTF-8，用 `Get-Content -Encoding UTF8` 读，否则中文断言消息乱码），
  里面 `message=` 已带断言文本，不必重复跑测试。
- 一次只改一处、跑一次：多改叠加后失败时无法区分是哪一处导致的。

给 agent 的推荐扩展任务（在实现功能时）

- 新增一种 token 类型时：更新 `Token` 定义、`Lexer.kt` 里的 `Lexer.scanToken()`，再让 `Parser` 能消费它，
  并在 `mlogix/test/compiler/*` 加单元测试。
- 新增一种 AST 节点类型时：更新 `compiler/ast/*.kt`、为调试打印更新 `ASTPrinter.kt`，并调整 `Parser.kt` 来构造该节点。
  记得给 span 赋值。

在哪里读语言行为与设计取舍

- 面向用户的语法与速查指南：`docs/grammar/index.md` 与 `docs/grammar/fast-learning.md`。
- 语义分析设计（约束生成 + 惰性求解）：`docs/design/semantic-analyzer.md`。
- 高层路线图与组件拆解（粗略）：`mlogix/TODO.md`。

可供检索的锚点

- 入口：`mlogix/src/Main.kt`
- 编译编排：`mlogix/src/compiler/Compiler.kt`
- Pass 流水线：`mlogix/src/compiler/pipeline/Pipeline.kt`
- Pass 契约：`mlogix/src/compiler/core/pass/CompilerPass.kt`、`mlogix/src/compiler/core/pass/PassId.kt`
- 编译上下文：`mlogix/src/compiler/core/CompilerContext.kt`、`mlogix/src/compiler/pipeline/CompilationContext.kt`
- 解析 pass：`mlogix/src/compiler/passes/parsing/ParsingPass.kt`
- 类型推断：`mlogix/src/compiler/passes/typing/*.kt`
- Lexer：`mlogix/src/compiler/passes/parsing/Lexer.kt`
- Parser：`mlogix/src/compiler/passes/parsing/Parser.kt`
- AST：`mlogix/src/compiler/ast/*.kt`
- 诊断：`mlogix/src/compiler/diagnostic/*.kt`

如果还需要更多上下文

- 先读 `Compiler.kt` 看各阶段怎么串起来的；再读 `SourceMap.kt`、`DiagHandler.kt` 与测试，理解期望的行为与报错格式。

许可证与文档：见 `mlogix/README.md` 与 `docs/`（面向语言使用者的文档）。
