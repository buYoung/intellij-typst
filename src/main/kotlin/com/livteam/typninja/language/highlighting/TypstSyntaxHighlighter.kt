package com.livteam.typninja.language.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.lexer.TypstLexer
import com.livteam.typninja.language.psi.TypstTokenTypes
import java.awt.Font

/**
 * Typst TextAttributesKey constants.
 *
 * Every color key falls back to a platform [DefaultLanguageHighlighterColors] / [CodeInsightColors] /
 * [HighlighterColors] constant — no hard-coded RGB is used here. The two contextual-modifier keys
 * ([STRONG], [EMPH]) carry a font-style-only default (bold / italic, no color) and are applied by
 * [TypstAnnotator] to `*strong*` / `_emph_` PSI nodes, per FDD 9.2 (theme-friendly modifiers).
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

    // ---- semantic (applied by TypstAnnotator via PSI, not by the lexer) ----
    // These are what visually separate a call site from a plain variable: the lexer classifies every
    // identifier as IDENTIFIER, so the distinction is only recoverable from the parsed tree. Each key
    // falls back to the matching platform semantic color, so themes stay in control (no hard-coded RGB).
    /** Callee of a `#f(...)` / `#obj.method(...)` call. */
    @JvmField
    val FUNCTION_CALL: TextAttributesKey = createTextAttributesKey(
        "TYPST_FUNCTION_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL
    )

    /** Name bound by `#let f(...) = …` (a function definition). */
    @JvmField
    val FUNCTION_DECLARATION: TextAttributesKey = createTextAttributesKey(
        "TYPST_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
    )

    /** Name bound by a plain `#let v = …` (a value definition). */
    @JvmField
    val VARIABLE_DEFINITION: TextAttributesKey = createTextAttributesKey(
        "TYPST_VARIABLE_DEFINITION", DefaultLanguageHighlighterColors.LOCAL_VARIABLE
    )

    /**
     * Read/usage of a variable — a `data` in `data.prefix`, an `x` used in an expression — resolved
     * by [TypstAnnotator] through the file-local reference to a plain `#let v = …` binding. Distinct
     * from [VARIABLE_DEFINITION] so users can color a definition and its reads differently, mirroring
     * the [FUNCTION_DECLARATION] (definition) vs [FUNCTION_CALL] (usage) split. Falls back to the
     * platform local-variable color; NOTE: that fallback is often the plain foreground in the default
     * scheme, so reads may look uncolored until the user assigns a color in the color-settings page.
     */
    @JvmField
    val VARIABLE: TextAttributesKey = createTextAttributesKey(
        "TYPST_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE
    )

    /** Closure / function parameter name (`(x, y) => …`, `it => …`, `#let f(x) = …`). */
    @JvmField
    val PARAMETER: TextAttributesKey = createTextAttributesKey(
        "TYPST_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER
    )

    /** Named argument key in a call, e.g. the `size` of `text(size: 12pt)`. */
    @JvmField
    val NAMED_ARGUMENT: TextAttributesKey = createTextAttributesKey(
        "TYPST_NAMED_ARGUMENT", DefaultLanguageHighlighterColors.PARAMETER
    )

    /** Field or dictionary member (`value.member`). */
    @JvmField
    val FIELD: TextAttributesKey = createTextAttributesKey(
        "TYPST_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD
    )

    /**
     * A Typst standard-library builtin FUNCTION used as a value (`image`, `table`, …) — mirrors
     * tinymist's `support.function.builtin` scope. Distinct from a user [FUNCTION_CALL] so stdlib names
     * read as "provided by Typst". Falls back to the platform static-method color (theme-following).
     */
    @JvmField
    val BUILTIN_FUNCTION: TextAttributesKey = createTextAttributesKey(
        "TYPST_BUILTIN_FUNCTION", DefaultLanguageHighlighterColors.STATIC_METHOD
    )

    /**
     * A Typst builtin TYPE (`int`, `length`, `color`, …) — mirrors tinymist's
     * `entity.name.type.primitive` scope. Falls back to the platform class-name color (theme-following).
     */
    @JvmField
    val BUILTIN_TYPE: TextAttributesKey = createTextAttributesKey(
        "TYPST_BUILTIN_TYPE", DefaultLanguageHighlighterColors.CLASS_NAME
    )

    // ---- escape ----
    @JvmField
    val ESCAPE: TextAttributesKey = createTextAttributesKey(
        "TYPST_ESCAPE", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE
    )

    // ---- reference (@target) ----
    /** Whole `@target` reference in markup, e.g. @fig:1, @sec:intro. */
    @JvmField
    val REFERENCE: TextAttributesKey = createTextAttributesKey(
        "TYPST_REFERENCE", DefaultLanguageHighlighterColors.METADATA
    )

    /** `<name>` label definition. */
    @JvmField
    val LABEL: TextAttributesKey = createTextAttributesKey(
        "TYPST_LABEL", DefaultLanguageHighlighterColors.METADATA
    )

    /** `https://…` bare auto-link. */
    @JvmField
    val LINK: TextAttributesKey = createTextAttributesKey(
        "TYPST_LINK", CodeInsightColors.HYPERLINK_ATTRIBUTES
    )

    // ---- markup structure markers ----
    /** Line-start `=`+ heading marker. */
    @JvmField
    val HEADING: TextAttributesKey = createTextAttributesKey(
        "TYPST_HEADING", DefaultLanguageHighlighterColors.KEYWORD
    )

    /** Line-start `- ` / `+ ` / `1. ` / `/ ` list, enum and term markers. */
    @JvmField
    val LIST_MARKER: TextAttributesKey = createTextAttributesKey(
        "TYPST_LIST_MARKER", DefaultLanguageHighlighterColors.KEYWORD
    )

    /** `~`, `--`, `---`, `...` shorthands and `'`/`"` smart quotes in markup. */
    @JvmField
    val MARKUP_ENTITY: TextAttributesKey = createTextAttributesKey(
        "TYPST_MARKUP_ENTITY", DefaultLanguageHighlighterColors.MARKUP_ENTITY
    )

    // ---- math ----
    /** Math identifier / text fragment (`alpha`, `x`, symbol runs). */
    @JvmField
    val MATH_IDENT: TextAttributesKey = createTextAttributesKey(
        "TYPST_MATH_IDENT", DefaultLanguageHighlighterColors.IDENTIFIER
    )

    /** Math operators: shorthands (`<=`, `->`), alignment point `&`, primes. */
    @JvmField
    val MATH_OPERATOR: TextAttributesKey = createTextAttributesKey(
        "TYPST_MATH_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN
    )

    // ---- contextual modifiers (applied by TypstAnnotator, not the lexer) ----
    // The default-attributes overload is intentional here: unlike the color keys above there is no
    // platform DefaultLanguageHighlighterColors constant that means "bold" or "italic" to fall back
    // to, so a font-style-only default (no color) is the self-contained, theme-friendly way to ship a
    // modifier key. It stays overridable by the user in the color-settings page.
    /** `*strong*` — bold font style only, no color, so themes stay in control. */
    @Suppress("DEPRECATION")
    @JvmField
    val STRONG: TextAttributesKey = createTextAttributesKey(
        "TYPST_STRONG", TextAttributes(null, null, null, null, Font.BOLD)
    )

    /** `_emph_` — italic font style only, no color. */
    @Suppress("DEPRECATION")
    @JvmField
    val EMPH: TextAttributesKey = createTextAttributesKey(
        "TYPST_EMPH", TextAttributes(null, null, null, null, Font.ITALIC)
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
 * [TypstTextAttributeKeys]. A fresh [TypstLexer] is returned per call so no stateful lexer instance
 * is shared. Highlighting is entirely file-local and does not depend on any project-level analysis.
 */
class TypstSyntaxHighlighter : SyntaxHighlighterBase() {

    /** Returns a fresh lexer instance for each highlighting pass. */
    override fun getHighlightingLexer(): Lexer = TypstLexer()

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
            TypstTokenTypes.FLOAT_LITERAL,
            TypstTokenTypes.NUMERIC_LITERAL -> arrayOf(TypstTextAttributeKeys.NUMBER)

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
            TypstTokenTypes.HAT,
            TypstTokenTypes.UNDERSCORE -> arrayOf(TypstTextAttributeKeys.OPERATOR)

            // whole @target reference in markup
            TypstTokenTypes.REF_MARKER -> arrayOf(TypstTextAttributeKeys.REFERENCE)

            // <name> label definition
            TypstTokenTypes.LABEL_DEF -> arrayOf(TypstTextAttributeKeys.LABEL)

            // https://… bare auto-link
            TypstTokenTypes.LINK -> arrayOf(TypstTextAttributeKeys.LINK)

            // heading marker (=), and list / enum / term markers
            TypstTokenTypes.HEADING_MARKER -> arrayOf(TypstTextAttributeKeys.HEADING)
            TypstTokenTypes.LIST_MARKER,
            TypstTokenTypes.ENUM_MARKER,
            TypstTokenTypes.TERM_MARKER -> arrayOf(TypstTextAttributeKeys.LIST_MARKER)

            // inline shorthands (~ -- --- ...) and smart quotes (' ")
            TypstTokenTypes.SHORTHAND,
            TypstTokenTypes.SMART_QUOTE -> arrayOf(TypstTextAttributeKeys.MARKUP_ENTITY)

            // trailing `\` line break behaves like an escape
            TypstTokenTypes.LINEBREAK -> arrayOf(TypstTextAttributeKeys.ESCAPE)

            // math tokens
            TypstTokenTypes.MATH_IDENT,
            TypstTokenTypes.MATH_TEXT -> arrayOf(TypstTextAttributeKeys.MATH_IDENT)
            TypstTokenTypes.MATH_SHORTHAND,
            TypstTokenTypes.MATH_ALIGN,
            TypstTokenTypes.MATH_PRIMES -> arrayOf(TypstTextAttributeKeys.MATH_OPERATOR)

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
