package mlogix.compiler.passes.parsing

import mlogix.compiler.ast.Stmt
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.SourceMapManager.SourceMap
import mlogix.compiler.core.pass.CompilerPass
import mlogix.compiler.core.pass.PassId

/**
 * 词法 + 语法分析 Pass（管道的起点）。
 *
 * 设计要点：
 * 这里把 Lexer 和 Parser **合成一个 Pass**，而不是拆成"LexerPass -> ParserPass"两个。
 * 原因：Parser 持有 Lexer，按需实时调用 [Lexer.scanToken] 扫描下一个 Token
 * （配合前瞻缓冲），**不会预先生成全部 Token 列表**再交给 Parser——那会产生不必要的中间
 * Token 数组，降低性能。保持这种"拉取式"词法扫描正是本 Pass 存在的意义。
 *
 * 输入 [SourceMap] → 输出原始 AST（[Stmt]），为后续 RESOLUTION / TYPE_INFERENCE 等 Pass 提供输入。
 */
class ParsingPass(
    private val parser: Parser,
) : CompilerPass<SourceMap, Stmt> {

    override val id: PassId = PassId.PARSE

    override val dependencies: Set<PassId> = emptySet()

    override fun execute(input: SourceMap, context: CompilerContext): Stmt {
        return parser.parse(input)
    }
}

