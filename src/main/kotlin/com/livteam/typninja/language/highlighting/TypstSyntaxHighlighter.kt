package com.livteam.typninja.language.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.lexer.TypstLexerAdapter
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * Typst TextAttributesKey constants.
 *
 * Every key falls back to a [DefaultLanguageHighlighterColors] or [HighlighterColors] platform
 * constant — no hard-coded RGB is used here.
 */
object TypstTextAttributeKeys {

    // ---- comments ----
    @JvmField
    val LINE_COMMENT: TextAttributesKey = createTextAttributesKey(
        "TYPST_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT
    )

    @JvmField
    val BLOCK_COMMENT: TextAttributesKey = createTextAttributesKey(
        "TYPST_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT
    )

    // ---- string / raw ----
    @JvmField
    val STRING: TextAttributesKey = createTextAttributesKey(
        "TYPST_STRING", DefaultLanguageHighlighterColors.STRING
    )

    @JvmField
    val RAW_TEXT: TextAttributesKey = createTextAttributesKey(
        "TYPST_RAW_TEXT", DefaultLanguageHighlighterColors.STRING
    )

    // ---- mode markers ----
    @JvmField
    val HASH: TextAttributesKey = createTextAttributesKey(
        "TYPST_HASH", DefaultLanguageHighlighterColors.MARKUP_TAG
    )

    @JvmField
    val DOLLAR: TextAttributesKey = createTextAttributesKey(
        "TYPST_DOLLAR", DefaultLanguageHighlighterColors.MARKUP_TAG
    )

    // ---- keywords ----
    @JvmField
    val KEYWORD: TextAttributesKey = createTextAttributesKey(
        "TYPST_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD
    )

    /** `true`, `false`, `none`, `auto` */
    @JvmField
    val KEYWORD_LITERAL: TextAttributesKey = createTextAttributesKey(
        "TYPST_KEYWORD_LITERAL", DefaultLanguageHighlighterColors.KEYWORD
    )

    // ---- literals ----
    @JvmField
    val NUMBER: TextAttributesKey = createTextAttributesKey(
        "TYPST_NUMBER", DefaultLanguageHighlighterColors.NUMBER
    )

    // ---- operators / delimiters ----
    @JvmField
    val OPERATOR: TextAttributesKey = createTextAttributesKey(
        "TYPST_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN
    )

    @JvmField
    val DELIMITER: TextAttributesKey = createTextAttributesKey(
        "TYPST_DELIMITER", DefaultLanguageHighlighterColors.BRACKETS
    )

    // ---- identifier ----
    @JvmField
    val IDENTIFIER: TextAttributesKey = createTextAttributesKey(
        "TYPST_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER
    )

    // ---- escape ----
    @JvmField
    val ESCAPE: TextAttributesKey = createTextAttributesKey(
        "TYPST_ESCAPE", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE
    )

    // ---- reference (@label) ----
    /** Sigil and label text of Typst references such as @fig:1, @sec:intro. */
    @JvmField
    val REFERENCE: TextAttributesKey = createTextAttributesKey(
        "TYPST_REFERENCE", DefaultLanguageHighlighterColors.METADATA
    )

    // ---- error ----
    @JvmField
    val BAD_CHARACTER: TextAttributesKey = createTextAttributesKey(
        "TYPST_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER
    )
}

/**
 * Lexer-based syntax highlighter for Typst.
 *
 * Maps the token types from [TypstTokenTypes] to [TextAttributesKey]s defined in
 * [TypstTextAttributeKeys]. A fresh [TypstLexerAdapter] is returned per call so no stateful
 * lexer instance is shared. Highlighting is entirely file-local and does not depend on any
 * project-level analysis.
 */
class TypstSyntaxHighlighter : SyntaxHighlighterBase() {

    /** Returns a fresh lexer instance for each highlighting pass. */
    override fun getHighlightingLexer(): Lexer = TypstLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        when (tokenType) {
            // comments
            TypstTokenTypes.LINE_COMMENT  -> arrayOf(TypstTextAttributeKeys.LINE_COMMENT)
            TypstTokenTypes.BLOCK_COMMENT -> arrayOf(TypstTextAttributeKeys.BLOCK_COMMENT)

            // string / raw
            TypstTokenTypes.STRING   -> arrayOf(TypstTextAttributeKeys.STRING)
            TypstTokenTypes.RAW_TEXT -> arrayOf(TypstTextAttributeKeys.RAW_TEXT)

            // mode markers
            TypstTokenTypes.HASH   -> arrayOf(TypstTextAttributeKeys.HASH)
            TypstTokenTypes.DOLLAR -> arrayOf(TypstTextAttributeKeys.DOLLAR)

            // keywords
            TypstTokenTypes.KW_LET,
            TypstTokenTypes.KW_SET,
            TypstTokenTypes.KW_SHOW,
            TypstTokenTypes.KW_CONTEXT,
            TypstTokenTypes.KW_IF,
            TypstTokenTypes.KW_ELSE,
            TypstTokenTypes.KW_FOR,
            TypstTokenTypes.KW_WHILE,
            TypstTokenTypes.KW_IN,
            TypstTokenTypes.KW_AND,
            TypstTokenTypes.KW_OR,
            TypstTokenTypes.KW_NOT,
            TypstTokenTypes.KW_RETURN,
            TypstTokenTypes.KW_IMPORT,
            TypstTokenTypes.KW_INCLUDE,
            TypstTokenTypes.KW_AS,
            TypstTokenTypes.KW_BREAK,
            TypstTokenTypes.KW_CONTINUE -> arrayOf(TypstTextAttributeKeys.KEYWORD)

            // boolean / none / auto keyword literals
            TypstTokenTypes.TRUE,
            TypstTokenTypes.FALSE,
            TypstTokenTypes.NONE,
            TypstTokenTypes.AUTO -> arrayOf(TypstTextAttributeKeys.KEYWORD_LITERAL)

            // numbers (integer, float, measurements)
            TypstTokenTypes.INTEGER_LITERAL,
            TypstTokenTypes.FLOAT_LITERAL -> arrayOf(TypstTextAttributeKeys.NUMBER)

            // operators (arithmetic, comparison, assignment, punctuation, math)
            TypstTokenTypes.PLUS,
            TypstTokenTypes.MINUS,
            TypstTokenTypes.STAR,
            TypstTokenTypes.SLASH,
            TypstTokenTypes.EQ,
            TypstTokenTypes.EQ_EQ,
            TypstTokenTypes.EXCL_EQ,
            TypstTokenTypes.LT,
            TypstTokenTypes.LT_EQ,
            TypstTokenTypes.GT,
            TypstTokenTypes.GT_EQ,
            TypstTokenTypes.PLUS_EQ,
            TypstTokenTypes.MINUS_EQ,
            TypstTokenTypes.STAR_EQ,
            TypstTokenTypes.SLASH_EQ,
            TypstTokenTypes.DOT,
            TypstTokenTypes.DOT_DOT,
            TypstTokenTypes.COMMA,
            TypstTokenTypes.SEMICOLON,
            TypstTokenTypes.COLON,
            TypstTokenTypes.ARROW,
            TypstTokenTypes.HAT -> arrayOf(TypstTextAttributeKeys.OPERATOR)

            // reference sigil (@label in markup mode)
            TypstTokenTypes.AT -> arrayOf(TypstTextAttributeKeys.REFERENCE)

            // delimiters — parens, braces, brackets
            TypstTokenTypes.LPAREN,
            TypstTokenTypes.RPAREN,
            TypstTokenTypes.LBRACE,
            TypstTokenTypes.RBRACE,
            TypstTokenTypes.LBRACKET,
            TypstTokenTypes.RBRACKET -> arrayOf(TypstTextAttributeKeys.DELIMITER)

            // identifier
            TypstTokenTypes.IDENTIFIER -> arrayOf(TypstTextAttributeKeys.IDENTIFIER)

            // backslash escape in markup / math
            TypstTokenTypes.ESCAPE -> arrayOf(TypstTextAttributeKeys.ESCAPE)

            // platform bad-character (unknown input in code context)
            TokenType.BAD_CHARACTER -> arrayOf(TypstTextAttributeKeys.BAD_CHARACTER)

            // TEXT (markup prose) and WHITE_SPACE: no custom coloring
            else -> emptyArray()
        }
}
