```
MLogiX/
├── compiler-core/                # 纯分析库（无 IO，无副作用）
│   ├── lexer/                    # 词法分析
│   ├── parser/                   # 语法分析
│   ├── ast/                      # AST 定义
│   ├── semantic/
│   │   ├── SymbolTable.kt        # 符号表
│   │   ├── TypeChecker.kt        # 类型检查
│   │   └── ReferenceGraph.kt     # 引用图
│   ├── evaluator/                # 编译期执行器
│   │   ├── Interpreter.kt        # AST 解释器
│   │   ├── NativeFunctions.kt    # 内置函数（编译期可用）
│   │   └── MacroExpander.kt      # 宏展开引擎
│   ├── queries/                  # 服务器查询函数
│   │   ├── DefinitionFinder.kt
│   │   ├── ReferenceFinder.kt
│   │   ├── Completer.kt
│   │   └── HoverProvider.kt
│   └── incremental/              # 增量解析
│       └── IncrementalParser.kt
│
├── language-server/              # LSP 服务器
│   ├── Server.kt                 # LSP 主循环
│   ├── DocumentManager.kt        # 文档缓存
│   ├── BackgroundAnalyzer.kt     # 后台分析线程
│   ├── RequestHandlers/          # LSP 请求处理器
│   │   ├── InitializeHandler.kt
│   │   ├── DocumentHandlers.kt
│   │   ├── QueryHandlers.kt      # 调用 compiler-core/queries
│   │   └── MacroHandlers.kt      # 宏展开请求（特殊）
│   └── WorkspaceManager.kt       # 跨文件符号表
│
└── build.gradle.kts              # 根项目构建
```