package mlogix.compiler.core.token

import java.util.EnumSet

enum class TokenType(private val keyword: String? = null) {
    // 关键字
    USE("use"),
    SET("set"), MACRO("macro"), CONST("const"),
    IF("if"), ELIF("elif"), ELSE("else"),
    FOR("for"), WHILE("while"), BREAK("break"), CONTINUE("continue"),
    STRUCT("struct"),
    MATCH("match"),
    FN("fn"), RETURN("return"),
    ENUM("enum"),

    // 标识符
    // xxx
    IDENTIFIER,

    // 字面量
    NUM, INT, COL, STR, TRUE("true"), FALSE("false"), NULL("null"),

    // 运算符
    // +  -      *     /      **         %        %%               //
    PLUS, MINUS, STAR, SLASH, STAR_STAR, PERCENT, PERCENT_PERCENT, SLASH_SLASH,

    //&  |   ^      <<   >>   >>>  ~
    AND, OR, CARET, SHL, SAR, SHR, TILDE,

    // ++      --
    PLUS_PLUS, MINUS_MINUS,

    // =
    ASSIGN,

    // +=        -=            *=           /=            **=               %=              %%=                     //=
    PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, STAR_STAR_ASSIGN, PERCENT_ASSIGN, PERCENT_PERCENT_ASSIGN, SLASH_SLASH_ASSIGN,

    // &=       |=         ^=            <<=         >>=         >>>=
    AND_ASSIGN, OR_ASSIGN, CARET_ASSIGN, SHL_ASSIGN, SAR_ASSIGN, SHR_ASSIGN,

    // ==  !=       ===       !==
    EQ_EQ, BANG_EQ, EQ_EQ_EQ, BANG_EQ_EQ,

    // <  >        <=       >=
    LESS, GREATER, LESS_EQ, GREATER_EQ,

    // &&    ||     !
    AND_AND, OR_OR, BANG,

    // :<      :=
    COLON_LESS, COLON_ASSIGN,

    // 分隔符
    // ->
    ARROW,

    // :   ;          ,      .
    COLON, SEMICOLON, COMMA, DOT,

    // (    )
    LPAREN, RPAREN,

    // [      ]
    LBRACKET, RBRACKET,

    // {    }
    LBRACE, RBRACE,

    // ?
    QUESTION_MARK,

    // 文档注释
    DOC_COMMENT,

    // 其他
    NEWLINE,  // 换行符
    UNKNOWN,  //未知
    ERROR,  //错误
    EOF; // 标识源码结尾

    override fun toString(): String {
        // 如果有对应的关键字，直接返回
        if (this.keyword != null) {
            return this.keyword
        }

        // 否则根据枚举值返回对应的符号
        return when (this) {
            PLUS -> "`+`"
            MINUS -> "`-`"
            STAR -> "`*`"
            SLASH -> "`/`"
            STAR_STAR -> "`**`"
            PERCENT -> "`%`"
            PERCENT_PERCENT -> "`%%`"
            SLASH_SLASH -> "`//`"
            AND -> "`&`"
            OR -> "`|`"
            CARET -> "`^`"
            SHL -> "`<<`"
            SAR -> "`>>`"
            SHR -> "`>>>`"
            TILDE -> "`~`"
            PLUS_PLUS -> "`++`"
            MINUS_MINUS -> "`--`"
            ASSIGN -> "`=`"
            PLUS_ASSIGN -> "`+=`"
            MINUS_ASSIGN -> "`-=`"
            STAR_ASSIGN -> "`*=`"
            SLASH_ASSIGN -> "`/=`"
            STAR_STAR_ASSIGN -> "`**=`"
            PERCENT_ASSIGN -> "`%=`"
            PERCENT_PERCENT_ASSIGN -> "`%%=`"
            SLASH_SLASH_ASSIGN -> "`//=`"
            AND_ASSIGN -> "`&=`"
            OR_ASSIGN -> "`|=`"
            CARET_ASSIGN -> "`^=`"
            SHL_ASSIGN -> "`<<=`"
            SAR_ASSIGN -> "`>>=`"
            SHR_ASSIGN -> "`>>>=`"
            EQ_EQ -> "`==`"
            BANG_EQ -> "`!=`"
            EQ_EQ_EQ -> "`===`"
            BANG_EQ_EQ -> "`!==`"
            LESS -> "`<`"
            GREATER -> "`>`"
            LESS_EQ -> "`<=`"
            GREATER_EQ -> "`>=`"
            AND_AND -> "`&&`"
            OR_OR -> "`||`"
            BANG -> "`!`"
            COLON_LESS -> "`:<`"
            COLON_ASSIGN -> "`:=`"
            ARROW -> "`->`"
            COLON -> "`:`"
            SEMICOLON -> "`;`"
            COMMA -> "`,`"
            DOT -> "`.`"
            LPAREN -> "`(`"
            RPAREN -> "`)`"
            LBRACKET -> "`[`"
            RBRACKET -> "`]`"
            LBRACE -> "`{`"
            RBRACE -> "`}`"
            QUESTION_MARK -> "`?`"
            NEWLINE -> "`\\n`"
            EOF -> "<eof>"
            else -> this.name.lowercase()
        }
    }

    companion object {
        val RECOVERY: Set<TokenType> = EnumSet.of(
            USE,
            SET, MACRO, CONST,
            IF,
            FOR, WHILE, BREAK, CONTINUE,
            STRUCT,
            MATCH,
            FN, RETURN,
            ENUM,
            RPAREN,
            RBRACKET,
            LBRACE, RBRACE,
            SEMICOLON, EOF,
        )

        val STMT_END: Set<TokenType> = EnumSet.of(
            NEWLINE, SEMICOLON, EOF
        )

        val BRACES: Set<TokenType> = EnumSet.of(
            LBRACE, RBRACE
        )

        val ASSIGNS: Set<TokenType> = EnumSet.of(
            ASSIGN,
            PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
            STAR_STAR_ASSIGN, PERCENT_ASSIGN, PERCENT_PERCENT_ASSIGN, SLASH_SLASH_ASSIGN,
            AND_ASSIGN, OR_ASSIGN, CARET_ASSIGN, SHL_ASSIGN, SAR_ASSIGN, SHR_ASSIGN
        )

        val LITERALS: Set<TokenType> = EnumSet.of(
            NUM, INT, COL, STR, TRUE, FALSE, NULL
        )

        val EQ_OPERATORS: Set<TokenType> = EnumSet.of(
            EQ_EQ, BANG_EQ, EQ_EQ_EQ, BANG_EQ_EQ
        )

        val COMPARISON_OPERATORS: Set<TokenType> = EnumSet.of(
            LESS, GREATER, LESS_EQ, GREATER_EQ
        )

        val RANGE_OPERATORS: Set<TokenType> = EnumSet.of(
            COLON_LESS, COLON_ASSIGN
        )

        val MUL_DIV_OPERATORS: Set<TokenType> = EnumSet.of(
            STAR, SLASH, PERCENT, PERCENT_PERCENT, SLASH_SLASH
        )

        val UNARY_OPERATORS: Set<TokenType> = EnumSet.of(
            MINUS, TILDE, BANG
        )

        val BINARY_OPERATORS: Set<TokenType> = EnumSet.of(
            PLUS, MINUS, STAR, SLASH, STAR_STAR, PERCENT, PERCENT_PERCENT, SLASH_SLASH,
            AND, OR, CARET, SHL, SAR, SHR,
            AND_AND, OR_OR
        )


        val ADD_SUB_OPERATORS: Set<TokenType> = EnumSet.of(
            PLUS, MINUS
        )

        val SEPARATORS: Set<TokenType> = EnumSet.of(
            COLON, SEMICOLON, COMMA, DOT,
            LPAREN, RPAREN,
            LBRACKET, RBRACKET,
            LBRACE, RBRACE
        )

        // 方便Lexer判断是否为关键字
        val KEYWORDS_MAP: Map<String, TokenType> = buildMap {
            for (type in TokenType.entries) {
                if (type.keyword != null) {
                    put(type.keyword, type)
                }
            }
        }
    }
}
