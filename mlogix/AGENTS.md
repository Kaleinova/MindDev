# AGENTS — 面向自动化编码 agent 的工作指引

目的：让「写代码 / 读代码」的 AI agent 用最小上下文立刻高效开工。

## 快速开始

所有命令都在**仓库根**（`MindDev/`）执行：

| 目的 | 命令 |
| --- | --- |
| 编译（跑 CLI，普通模式） | `.\gradlew.bat :mlogix:compile` |
| 编译 + debug 输出（含 AST） | `.\gradlew.bat :mlogix:compile-debug` |
| 只看 token | `.\gradlew.bat :mlogix:tokenize-debug` |
| 全量测试 | `.\gradlew.bat :mlogix:test` |
| 单个测试类 | `.\gradlew.bat :mlogix:test --tests "mlogix.compiler.LexerTest"` |

**快速试错**：直接改 `mlogix/mlogix_test/test.mlx`（无需保留原内容），再跑 `:mlogix:compile` 看词法/语法/类型推断报错。
`Compiler.compile()` 会遍历项目下所有 `.mlx` 文件（`mlogix/src/compiler/Compiler.kt`），不必手动指定。

## 构建前置条件（先读这节，能省掉几轮试错）

- **`gradlew.bat` 只在仓库根**。在 `mlogix/` 里执行会报「无法将 `.\gradlew.bat` 项识别为 cmdlet…」；子目录要用
  `..\gradlew.bat`，但没必要——留在仓库根最省事（JavaExec 的相对路径按仓库根解析，已实测 `..\gradlew.bat :mlogix:compile`
  同样能读到 `mlogix/mlogix_test/test.mlx`）。
- **任务名**：`:mlogix:compile` 是精确写法；裸 `compile` 等效（根工程没这个任务，Gradle 任务名匹配会落到 `:mlogix:compile`）。
  但**测试必须写 `:mlogix:test`**：裸 `test` 会跑所有子工程（`:minddev:test` + `:mlogix:test`），既慢又混入无关失败。
- **构建要写工作区外的 `~/.gradle`**（守护进程、依赖缓存、wrapper dist）。在限制写工作区的沙箱里第一次构建会以
  `FileNotFoundException: ...\.gradle\wrapper\dists\...\gradle-9.7.0-bin.zip.lck (拒绝访问)` 失败——这不是工程问题，
  也别换写法重试：直接以更宽权限（`danger-full-access`）重跑**同一条**命令一次。
- **中文输出乱码**：控制台本身是 UTF-8，但 fork 出的 JVM 用平台默认字符集（中文 Windows 即 GBK）写 stdout，于是
  `类型不匹配` 变成 `���Ͳ�ƥ��`；Gradle 打印**构建脚本里的中文**（如任务 `description`）也会乱。跑任何命令前先设
  `$env:JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"`。单测断言走 `I18N.bundle`，与字符集无关。
- **全量 `:mlogix:test` 只需约 10 秒**（守护进程热起来后），比逐个类跑划算；改完直接全量跑，别按类试。
- **诊断断言一律用 `I18N.bundle.get(key)` / `I18N.bundle.format(key, args)`**，不要硬编码文案：本机 CLI 走中文
  `bundle_zh_CN.properties`，单测里 `I18N.bundle` 初始化为英文 `bundle.properties`，两边文案不同。

## 文件编码 — 必须遵守

- 所有源码/测试/资源文件一律 **UTF-8 无 BOM**。含中文注释的 `.kt` / `.properties` 尤其敏感。
- **绝不用 PowerShell(版本 < 6.0) 重写含中文的源文件**：其 `Get-Content` / `Set-Content` 未显式指定编码时用
  **ANSI（中文系统即 GBK）**，对 UTF-8 文件读/写一次就**有损重编码**——多数字节因 GBK 双向映射幸存，但无法配对成 GBK
  的字节被替换成 `?`（0x3F），文件不再是合法 UTF-8，IDEA 报「该文件以错误的编码加载: 'UTF-8'」，中文注释变乱码且
  **不可自动无损还原**。
- 若确需用 PowerShell 处理文件，读写都显式加 `-Encoding UTF8`（读 `Get-Content -Encoding UTF8`，写
  `Set-Content -Encoding UTF8`）。

## 整体架构

> 本文件路径都是**仓库根相对**。源码根是 `mlogix/src/`，包名才是 `mlogix.*`——**不存在 `mlogix/src/mlogix/` 这一层**
> （旧文档按包名臆造过，别踩）。

- 本仓库是一个小型语言前端（lexer → parser → AST → 语义分析 → 问题报告）。
- 入口 `mlogix/src/Main.kt`（解析 CLI 参数并调用 `Compiler`）；编排 `mlogix/src/compiler/Compiler.kt`（创建
  `Lexer`、`Parser`、`DiagHandler`、`SourceMap`，装配 `Pipeline` 并按文件跑各阶段）。**加新功能以 `Compiler.kt` 为准。**
- **Pass 流水线** `mlogix/src/compiler/pipeline/Pipeline.kt`：按序执行一串 `CompilerPass`（契约
  `mlogix/src/compiler/core/pass/CompilerPass.kt`，id 见 `mlogix/src/compiler/core/pass/PassId.kt`）。各 pass 通过 IR
  交换数据、共享 `CompilerContext`（`mlogix/src/compiler/core/CompilerContext.kt`，实现
  `mlogix/src/compiler/pipeline/CompilationContext.kt`）。**新增 pass 就加到 `Compiler.compile()` 里那个 `Seq`。**
  入口按文件：`pipeline.run(sourceFile, context)`（输入 `SourceFile`，输出 `Stmt`）。
- pass id：`PARSE`、`RESOLUTION`、`DESUGAR`、`TYPE_INFERENCE`、`DATAFLOW`、`EXHAUSTIVENESS`。
  对应目录在 `mlogix/src/compiler/passes/` 下，其中 `desugar/`、`dataflow/`、`borrowck/` 已搭好但基本为空。
- **词法 + 语法（同一 pass）** `mlogix/src/compiler/passes/parsing/`：`Lexer.kt` 把输入切成带 `Span` 的 `Token`；
  `Parser.kt` 构建 `Expr`/`Stmt`；`ParsingPass.kt` 包成 `CompilerPass<SourceMap, Stmt>`。
  **关键设计：Parser 持有 Lexer，通过前瞻缓冲按需实时调用 `Lexer.scanToken()`——绝不预生成完整 token 列表再交给
  Parser**（省掉中间数组开销）。错误恢复/回溯依赖 Lexer 快照（`Lexer.createSnapshot/restoreSnapshot`）。
- **类型系统** `mlogix/src/compiler/core/type/`：`Type` 是 **sealed 代数结构**（`Con`/`Var`/`Func`/`Arr`/`TupleType`/
  `Unknown`/`Error`），结构相等用 `==`；`TypeVar` 即 `Type.Var(index: Int)`，并查集按 Int 索引（`arc.struct.IntMap`）；
  `TypeScheme`（∀ 多态）提供 `instantiate/generalize/freeTypeVars`；`TypeVisitor` 是统一遍历器（occurs check、自由变量
  收集都用它）。**类型系统绝不 throw**，出错注入 `Type.Error` 抑制级联错误。
- **语义分析 / 类型推断** `mlogix/src/compiler/passes/typing/`：`TypeInferencer`（约束生成 + 经 `TypeSolver` 惰性求解，
  配套 `Constraint`、`InferResult`），包装为 `TypeInferencePass`。
- **AST** `mlogix/src/compiler/ast/`（`Expr`/`Stmt`；`ASTPrinter.kt` 用于调试打印）。
  **源码映射与诊断** `mlogix/src/compiler/core/SourceMap.kt`、`mlogix/src/compiler/diagnostic/Diagnostic.kt`、
  `mlogix/src/compiler/diagnostic/DiagHandler.kt`——错误与告警都关联 `Span`，打印时带上下文行。

## 项目约定与重要模式

- **纯 Kotlin 工程**：源码（含测试）全在 `mlogix/src` 与 `mlogix/test` 下，二者同时是 Kotlin 源根（见
  `mlogix/build.gradle.kts`），package 为 `mlogix`。新增源码放 `mlogix/src/…`，测试放 `mlogix/test/…`。
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
    - 函数式接口用 `arc.func.Cons` / `Prov` / `Boolf`；`java.util.function.Consumer` 仅用于与标准库对接的解耦场景。
    - 日志用 `arc.util.Log`（见 `mlogix/src/util/Log.kt`、`mlogix/src/util/Ansi.kt`）。
    - 颜色字面量（`0%RRGGBB` / `0%colorName`）由 Lexer 转为 `arc.graphics.Color` 的 double-bits（`Color.toDoubleBits`）。
  - `Seq`使用须知:
      - 不带参数的`Seq`默认初始化16个位置，当能够预测数据数量甚至确定数据数量为0时（比如函数参数），填入预估的初始大小参数，不要往小估。
      - 明确该Seq不再更改时，使用`shrink()`方法压缩占用。
      - 批量添加数据使用`addAll`。
      - 批量修改数据并返回新`Seq`使用`map()`方法。
- 语言位置：所有 AST 节点与诊断统一用 `Span`（`mlogix/src/compiler/core/span/Span.kt`）。改 AST 节点要保证 span 正确
  （用 `Span.between`，或直接透传 `token.span`）。
- 问题报告：用 `SourceFile` 构造 `Diagnostic`，再经 `DiagHandler.addError/addWarning` 收集；`DiagHandler` 是测试与
  `Compiler` 检查失败计数的唯一入口。
- 各 pass **绝不能互相直接引用**；通过 IR 交换数据、经 `CompilerContext` 共享状态（open-closed principle）。分层依赖
  方向见 `mlogix/src/compiler/Compiler.kt` 里 `Seq` 的装配顺序与 `PassId` 的枚举顺序。
- 测试用 JUnit 5（配置见 `mlogix/build.gradle.kts`）：`mlogix/test/compiler/LexerTest.kt` 展示如何实例化 `Lexer` 并断言
  token 序列；`mlogix/test/compiler/ParserTest.kt` 展示 `parser.parse("2 + 3")`。

使用类似rust的面向对象系统

## 调试诊断/语义问题的推荐手法（别用「改一版跑一次」盲猜）

- **短反馈回路**：改 `mlogix/mlogix_test/test.mlx` → `:mlogix:compile`（约 5 秒，打印真实诊断）。
- **诊断计数/文案**：在 `mlogix/test/compiler/*Test.kt` 里加一个临时 `@Test`，复用该文件已有的 `analyze(source)`：
  ```kotlin
  @Test fun zzTempDump() {
      analyze(""" ... """.trimIndent())
      println("TMP-ERRORS: " + context.diagHandler.errors.joinToString(" | ") { it.message })
      println("TMP-WARNINGS: " + context.diagHandler.warnings.joinToString(" | ") { it.message })
  }
  ```
  跑 `.\gradlew.bat :mlogix:test --tests "mlogix.compiler.MatchTest" --console=plain -i 2>&1 | Select-String "TMP-"`
  只看自己那几行。**临时测试用完必须删掉**，再跑一次全量确认干净。
- **先插桩确认数据，再改逻辑**：`Type.pretty()`、`Seq.joinToString` 足以定位「数据到没到这儿」。曾有教训：没先确认实参
  就连续盲改 `useful()`，白跑 5 次构建；插桩后发现真因是「枚举名压根没参与比较」。
- **失败先读** `mlogix/build/test-results/test/TEST-*.xml`（UTF-8，用 `Get-Content -Encoding UTF8` 读，否则中文断言消息
  乱码）——`message=` 已带断言文本，不必重跑测试。
- 一次只改一处、跑一次：多改叠加后失败时无法区分是哪一处导致的。

## 集成点与外部依赖

- 依赖声明在 `mlogix/build.gradle.kts`：arc-core 与 Mindustry core 来自自定义 Maven 端点。工程固定 `mindustryVersion`
  并用 resolution strategy 约束 Arc 版本——改依赖时保留这套模式（见 `mlogix/build.gradle.kts` 第 35–59 行）。
- 工具链：Java 17（`jvmTarget = JVM_17`，设在根 `build.gradle.kts`）。**CI/agent 必须以 JDK 17 为目标。**
- 测试产物在 `mlogix/build/test-results`，报告在 `mlogix/build/reports/tests`。

## 在哪里读语言行为与设计取舍

- 面向用户的语法与速查指南：`docs/grammar/index.md`、`docs/grammar/fast-learning.md`。
- 语义分析设计（约束生成 + 惰性求解）：`docs/design/semantic-analyzer.md`。
- parser 错误恢复思路：`mlogix/src/compiler/TODO-scope_stack.md`。
- 高层路线图：`mlogix/TODO.md`。
- 语言使用者文档与许可证：`mlogix/README.md`、`docs/`。

## 若还需更多上下文

- 先读 `Compiler.kt` 看各阶段如何串联，再读 `SourceMap.kt`、`DiagHandler.kt` 与测试，理解期望行为与报错格式。

## 推荐扩展任务

- 新增 token 类型：更新 `Token` 定义、`Lexer.kt` 的 `Lexer.scanToken()`，让 `Parser` 能消费它，并在
  `mlogix/test/compiler/*` 加单测。
- 新增 AST 节点：更新 `mlogix/src/compiler/ast/*.kt`、`ASTPrinter.kt`，调整 `Parser.kt` 构造该节点，**并确保 span 赋值**。
