package com.livteam.typninja.language.psi

import com.intellij.psi.tree.IElementType

/**
 * Stable Typst token-type contract (Typst 0.15.0 MVP baseline).
 *
 * Every field is exposed as a `public static final IElementType` (via [JvmField]) so it can be
 * referenced uniformly as `TypstTokenTypes.<NAME>` from the hand-written lexer and parser.
 *
 * These names are a CONTRACT consumed verbatim by:
 *   - phase 05 (parser / `Typst.bnf` token declarations),
 *   - phase 06 (syntax highlighting token -> color-key mapping),
 *   - phase 07 (commenter — see [LINE_COMMENT] / [BLOCK_COMMENT]).
 *
 * Whitespace and unknown input intentionally do NOT have entries here: the lexer always returns
 * `com.intellij.psi.TokenType.WHITE_SPACE` and `com.intellij.psi.TokenType.BAD_CHARACTER` for
 * those, per the IntelliJ custom-language pipeline conventions.
 */
object TypstTokenTypes {

    // ---- comment ----
    /** `// ... ` single-line comment (distinct token so the commenter can toggle line comments). */
    @JvmField val LINE_COMMENT: IElementType = TypstTokenType("LINE_COMMENT")
    /** `/* ... */` block comment (single token; nesting/unterminated handled conservatively). */
    @JvmField val BLOCK_COMMENT: IElementType = TypstTokenType("BLOCK_COMMENT")

    // ---- string ----
    /** `"..."` code/math string literal; bounded to a single line for recovery. */
    @JvmField val STRING: IElementType = TypstTokenType("STRING")

    // ---- raw ----
    /** Inline `` `...` `` or fenced ```` ```...``` ```` raw text (one token covering the whole span). */
    @JvmField val RAW_TEXT: IElementType = TypstTokenType("RAW_TEXT")

    // ---- code marker ----
    /** `#` — markup-to-code expression marker. */
    @JvmField val HASH: IElementType = TypstTokenType("HASH")

    // ---- math marker ----
    /** `$` — math-mode delimiter (opens and closes math). */
    @JvmField val DOLLAR: IElementType = TypstTokenType("DOLLAR")

    // ---- delimiter ----
    @JvmField val LPAREN: IElementType = TypstTokenType("LPAREN")
    @JvmField val RPAREN: IElementType = TypstTokenType("RPAREN")
    @JvmField val LBRACE: IElementType = TypstTokenType("LBRACE")
    @JvmField val RBRACE: IElementType = TypstTokenType("RBRACE")
    @JvmField val LBRACKET: IElementType = TypstTokenType("LBRACKET")
    @JvmField val RBRACKET: IElementType = TypstTokenType("RBRACKET")

    // ---- operator ----
    @JvmField val PLUS: IElementType = TypstTokenType("PLUS")
    @JvmField val MINUS: IElementType = TypstTokenType("MINUS")
    @JvmField val STAR: IElementType = TypstTokenType("STAR")
    @JvmField val SLASH: IElementType = TypstTokenType("SLASH")
    @JvmField val EQ: IElementType = TypstTokenType("EQ")
    @JvmField val EQ_EQ: IElementType = TypstTokenType("EQ_EQ")
    @JvmField val EXCL_EQ: IElementType = TypstTokenType("EXCL_EQ")
    @JvmField val LT: IElementType = TypstTokenType("LT")
    @JvmField val LT_EQ: IElementType = TypstTokenType("LT_EQ")
    @JvmField val GT: IElementType = TypstTokenType("GT")
    @JvmField val GT_EQ: IElementType = TypstTokenType("GT_EQ")
    @JvmField val PLUS_EQ: IElementType = TypstTokenType("PLUS_EQ")
    @JvmField val MINUS_EQ: IElementType = TypstTokenType("MINUS_EQ")
    @JvmField val STAR_EQ: IElementType = TypstTokenType("STAR_EQ")
    @JvmField val SLASH_EQ: IElementType = TypstTokenType("SLASH_EQ")
    @JvmField val DOT: IElementType = TypstTokenType("DOT")
    @JvmField val DOT_DOT: IElementType = TypstTokenType("DOT_DOT")
    @JvmField val COMMA: IElementType = TypstTokenType("COMMA")
    @JvmField val SEMICOLON: IElementType = TypstTokenType("SEMICOLON")
    @JvmField val COLON: IElementType = TypstTokenType("COLON")
    @JvmField val ARROW: IElementType = TypstTokenType("ARROW")
    @JvmField val HAT: IElementType = TypstTokenType("HAT")
    /** `_` — markup emphasis toggle / code destructuring placeholder / math subscript. */
    @JvmField val UNDERSCORE: IElementType = TypstTokenType("UNDERSCORE")

    // ---- markup markers (line-start / inline) ----
    /** Blank line (>= 2 newlines). A REAL token (not whitespace) marking a paragraph boundary. */
    @JvmField val PARBREAK: IElementType = TypstTokenType("PARBREAK")
    /** Trailing `\` line break in markup. */
    @JvmField val LINEBREAK: IElementType = TypstTokenType("LINEBREAK")
    /** `~`, `--`, `---`, `...` markup shorthands. */
    @JvmField val SHORTHAND: IElementType = TypstTokenType("SHORTHAND")
    /** `'` / `"` smart quote in markup. */
    @JvmField val SMART_QUOTE: IElementType = TypstTokenType("SMART_QUOTE")
    /** Line-start `=`+ heading marker. */
    @JvmField val HEADING_MARKER: IElementType = TypstTokenType("HEADING_MARKER")
    /** Line-start `- ` list marker. */
    @JvmField val LIST_MARKER: IElementType = TypstTokenType("LIST_MARKER")
    /** Line-start `+ ` / `1. ` enum marker. */
    @JvmField val ENUM_MARKER: IElementType = TypstTokenType("ENUM_MARKER")
    /** Line-start `/ ` term-list marker. */
    @JvmField val TERM_MARKER: IElementType = TypstTokenType("TERM_MARKER")
    /** `http://…` / `https://…` bare auto-link. */
    @JvmField val LINK: IElementType = TypstTokenType("LINK")
    /** `<name>` label definition. */
    @JvmField val LABEL_DEF: IElementType = TypstTokenType("LABEL_DEF")
    /** `@target` reference emitted as one whole-span token (there is no separate `@`-sigil token). */
    @JvmField val REF_MARKER: IElementType = TypstTokenType("REF_MARKER")

    // ---- math tokens ----
    /** Math identifier (`alpha`, `x`). */
    @JvmField val MATH_IDENT: IElementType = TypstTokenType("MATH_IDENT")
    /** Math single fragment / symbol run. */
    @JvmField val MATH_TEXT: IElementType = TypstTokenType("MATH_TEXT")
    /** Math shorthand (`<=`, `->`, `!=`, …). */
    @JvmField val MATH_SHORTHAND: IElementType = TypstTokenType("MATH_SHORTHAND")
    /** Math alignment point `&`. */
    @JvmField val MATH_ALIGN: IElementType = TypstTokenType("MATH_ALIGN")
    /** Math primes run (`a'`, `a''`). */
    @JvmField val MATH_PRIMES: IElementType = TypstTokenType("MATH_PRIMES")

    // ---- identifier ----
    @JvmField val IDENTIFIER: IElementType = TypstTokenType("IDENTIFIER")

    // ---- literal (numeric + keyword literals) ----
    @JvmField val INTEGER_LITERAL: IElementType = TypstTokenType("INTEGER_LITERAL")
    /** Pure float / exponent (e.g. `1.5`, `2e10`). Measurements are split out into [NUMERIC_LITERAL]. */
    @JvmField val FLOAT_LITERAL: IElementType = TypstTokenType("FLOAT_LITERAL")
    /** Length / angle / ratio / fraction measurement (e.g. `12pt`, `50%`, `30deg`, `2em`, `1fr`). */
    @JvmField val NUMERIC_LITERAL: IElementType = TypstTokenType("NUMERIC_LITERAL")
    @JvmField val TRUE: IElementType = TypstTokenType("TRUE")
    @JvmField val FALSE: IElementType = TypstTokenType("FALSE")
    @JvmField val NONE: IElementType = TypstTokenType("NONE")
    @JvmField val AUTO: IElementType = TypstTokenType("AUTO")

    // ---- keyword ----
    @JvmField val KW_LET: IElementType = TypstTokenType("KW_LET")
    @JvmField val KW_SET: IElementType = TypstTokenType("KW_SET")
    @JvmField val KW_SHOW: IElementType = TypstTokenType("KW_SHOW")
    @JvmField val KW_CONTEXT: IElementType = TypstTokenType("KW_CONTEXT")
    @JvmField val KW_IF: IElementType = TypstTokenType("KW_IF")
    @JvmField val KW_ELSE: IElementType = TypstTokenType("KW_ELSE")
    @JvmField val KW_FOR: IElementType = TypstTokenType("KW_FOR")
    @JvmField val KW_WHILE: IElementType = TypstTokenType("KW_WHILE")
    @JvmField val KW_IN: IElementType = TypstTokenType("KW_IN")
    @JvmField val KW_AND: IElementType = TypstTokenType("KW_AND")
    @JvmField val KW_OR: IElementType = TypstTokenType("KW_OR")
    @JvmField val KW_NOT: IElementType = TypstTokenType("KW_NOT")
    @JvmField val KW_RETURN: IElementType = TypstTokenType("KW_RETURN")
    @JvmField val KW_IMPORT: IElementType = TypstTokenType("KW_IMPORT")
    @JvmField val KW_INCLUDE: IElementType = TypstTokenType("KW_INCLUDE")
    @JvmField val KW_AS: IElementType = TypstTokenType("KW_AS")
    @JvmField val KW_BREAK: IElementType = TypstTokenType("KW_BREAK")
    @JvmField val KW_CONTINUE: IElementType = TypstTokenType("KW_CONTINUE")

    // ---- markup ----
    /** A run of literal markup text (prose) in document/content context. */
    @JvmField val TEXT: IElementType = TypstTokenType("TEXT")
    /** `\X` backslash escape sequence (markup / math). */
    @JvmField val ESCAPE: IElementType = TypstTokenType("ESCAPE")
}
