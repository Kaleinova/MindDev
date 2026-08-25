package mlogix.compiler.passes.parsing

import arc.struct.Seq
import mlogix.compiler.core.CompilerContext
import mlogix.compiler.core.SourceMap.SourceFile
import mlogix.compiler.core.pass.CompilerPass
import mlogix.compiler.core.pass.PassId
import mlogix.compiler.core.token.Token

/**
 * 词法分析Pass
 * 仅在 [mlogix.compiler.core.CompilationMode.TOKENIZATION] 时使用，否则被包含在 [ParsingPass] 中
 *
 * 输入 [SourceFile] → 输出 `Seq<Token>`
 */
class TokenizationPass(
    private val lexer: Lexer,
) : CompilerPass<SourceFile, Seq<Token>> {

    override val id: PassId = PassId.TOKENIZATION

    override val dependencies: Set<PassId> = emptySet()

    override fun execute(input: SourceFile, context: CompilerContext): Seq<Token> {
        return lexer.tokenize(input.source)
    }
}
