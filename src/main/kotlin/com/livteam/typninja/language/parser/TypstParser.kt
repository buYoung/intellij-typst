package com.livteam.typninja.language.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * Hand-written recoverable parser for the Typst MVP PSI tree.
 *
 * Why hand-written: it consumes the [TypstTokenTypes] instances emitted by the lexer DIRECTLY
 * (every branch compares `builder.tokenType` to a [TypstTokenTypes] constant), so there is no
 * second, mismatched token set as Grammar-Kit would generate. The lexer already does the
 * markup/code/math state switching; this parser regroups the flat token stream into the composite
 * regions in [TypstElementTypes] and never lets incomplete input collapse the tree.
 *
 * Recovery invariants:
 *  - Every parse step consumes at least one token, so the top loop always terminates and every
 *    token ends up in the tree (the file node always spans the whole document).
 *  - Unclosed groups simply end their node (low-noise), they do not swallow the rest of the file
 *    structurally beyond their natural token run.
 *  - Math (which may span lines) is additionally bounded at a blank line, mirroring the lexer's own
 *    recovery, so an unclosed `$` cannot eat following paragraphs. A markup content block `[ ... ]`
 *    is bounded the same way, so an unclosed `[` cannot absorb the rest of the file.
 *  - Strings are single lexer tokens (already line-bounded), so an unclosed string is one leaf.
 *  - Stray closers and BAD_CHARACTER become bounded single-token error elements.
 *  - Recursive descent is depth-guarded ([MAX_RECURSION_DEPTH]); pathological input such as
 *    `[[[[...` or `#(((((...` thousands deep stops descending and consumes the remaining tokens
 *    flatly, so it cannot StackOverflow while still producing a valid tree.
 *
 * Note: whitespace and comment tokens are skipped by the PsiBuilder (they are declared in the
 * parser definition's whitespace/comment sets) and auto-attached to adjacent nodes, so this parser
 * only ever sees meaningful tokens.
 */
class TypstParser : PsiParser {

    /** Current recursive-descent depth; capped by [MAX_RECURSION_DEPTH] to avoid StackOverflow. */
    private var recursionDepth = 0

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        recursionDepth = 0
        val fileMarker = builder.mark()
        while (!builder.eof()) {
            parseMarkupItem(builder)
        }
        fileMarker.done(root)
        return builder.treeBuilt
    }

    // ---- markup (document) context ----

    /** Parse one construct in markup context. Always advances at least one token. */
    private fun parseMarkupItem(builder: PsiBuilder) {
        when (builder.tokenType) {
            TypstTokenTypes.HASH -> parseCodeExpression(builder)
            TypstTokenTypes.DOLLAR -> parseMath(builder)
            TypstTokenTypes.AT -> parseLabel(builder)
            TypstTokenTypes.LBRACKET -> parseMarkupContentBlock(builder)
            TypstTokenTypes.RAW_TEXT -> wrapSingleToken(builder, TypstElementTypes.RAW)
            TypstTokenTypes.STRING -> wrapSingleToken(builder, TypstElementTypes.STRING_LITERAL)
            TypstTokenTypes.TEXT, TypstTokenTypes.ESCAPE -> parseMarkupText(builder)
            else -> consumeErrorToken(builder, "Unexpected token")
        }
    }

    /** Group a run of consecutive prose tokens (TEXT / ESCAPE) into one [TypstElementTypes.MARKUP]. */
    private fun parseMarkupText(builder: PsiBuilder) {
        val marker = builder.mark()
        while (!builder.eof() &&
            (builder.tokenType == TypstTokenTypes.TEXT || builder.tokenType == TypstTokenTypes.ESCAPE)
        ) {
            builder.advanceLexer()
        }
        marker.done(TypstElementTypes.MARKUP)
    }

    /** `@name` reference-like sigil. The name (if directly adjacent) is a single TEXT token. */
    private fun parseLabel(builder: PsiBuilder) {
        val marker = builder.mark()
        builder.advanceLexer() // AT
        // Consume the label name only if it is immediately adjacent (no whitespace after '@').
        if (builder.rawLookup(0) == TypstTokenTypes.TEXT) {
            builder.advanceLexer()
        }
        marker.done(TypstElementTypes.LABEL)
    }

    /**
     * A `[ ... ]` content block: its body is parsed as nested markup. Bounded at the matching `]`, a
     * blank line (so an unclosed `[` cannot swallow the rest of the file), or EOF. Beyond the
     * recursion cap the body is skipped so nested `[` cannot StackOverflow.
     */
    private fun parseMarkupContentBlock(builder: PsiBuilder) {
        val marker = builder.mark()
        builder.advanceLexer() // [
        if (recursionDepth < MAX_RECURSION_DEPTH) {
            recursionDepth++
            while (!builder.eof() && builder.tokenType != TypstTokenTypes.RBRACKET) {
                if (hasBlankLineBefore(builder)) break // recover an unclosed content block at a paragraph break
                parseMarkupItem(builder)
            }
            recursionDepth--
        }
        if (!builder.eof() && builder.tokenType == TypstTokenTypes.RBRACKET) {
            builder.advanceLexer() // ]
        }
        marker.done(TypstElementTypes.CONTENT_BLOCK)
    }

    // ---- code context ----

    /** A `#`-introduced code expression. */
    private fun parseCodeExpression(builder: PsiBuilder) {
        val marker = builder.mark()
        builder.advanceLexer() // HASH
        parseCodeBody(builder)
        marker.done(TypstElementTypes.CODE_EXPRESSION)
    }

    /**
     * Consume a run of code tokens, recursing into balanced groups. Stops at a markup-transition
     * token, a closer (handled by the enclosing group), a paragraph break, or EOF.
     */
    private fun parseCodeBody(builder: PsiBuilder) {
        while (!builder.eof()) {
            val type = builder.tokenType ?: break
            if (isCodeBodyStopper(type)) break
            if (isCloser(type)) break
            if (hasBlankLineBefore(builder)) break
            when (type) {
                TypstTokenTypes.LPAREN ->
                    parseGroup(builder, TypstTokenTypes.RPAREN, TypstElementTypes.PAREN_GROUP)
                TypstTokenTypes.LBRACE ->
                    parseGroup(builder, TypstTokenTypes.RBRACE, TypstElementTypes.CODE_BLOCK)
                // A content block opened from code lexes its body as MARKUP (the lexer switches to
                // markup after `[`), so parse it as markup, not as more code.
                TypstTokenTypes.LBRACKET -> parseMarkupContentBlock(builder)
                TypstTokenTypes.STRING -> wrapSingleToken(builder, TypstElementTypes.STRING_LITERAL)
                TypstTokenTypes.RAW_TEXT -> wrapSingleToken(builder, TypstElementTypes.RAW)
                TokenType.BAD_CHARACTER -> consumeErrorToken(builder, "Unexpected character")
                else -> builder.advanceLexer() // identifier, keyword, literal, operator, delimiter-pair contents
            }
        }
    }

    /**
     * A balanced group `(...)`, `{...}`, or `[...]` whose body is code. If the matching [closer] is
     * absent the node ends quietly (recoverable), it does not consume unrelated trailing tokens.
     */
    private fun parseGroup(builder: PsiBuilder, closer: IElementType, type: IElementType) {
        val marker = builder.mark()
        builder.advanceLexer() // opener
        if (recursionDepth < MAX_RECURSION_DEPTH) {
            recursionDepth++
            parseCodeBody(builder)
            recursionDepth--
        }
        if (!builder.eof() && builder.tokenType == closer) {
            builder.advanceLexer() // closer
        }
        marker.done(type)
    }

    // ---- math context ----

    /** A `$ ... $` math region. Bounded at the closing `$`, a blank line, or EOF. */
    private fun parseMath(builder: PsiBuilder) {
        val marker = builder.mark()
        builder.advanceLexer() // opening DOLLAR
        while (!builder.eof()) {
            val type = builder.tokenType ?: break
            if (type == TypstTokenTypes.DOLLAR) {
                builder.advanceLexer() // closing DOLLAR
                break
            }
            if (hasBlankLineBefore(builder)) break // recover an unclosed math at a paragraph break
            when (type) {
                TypstTokenTypes.HASH -> parseCodeExpression(builder) // code embedded in math
                TypstTokenTypes.STRING -> wrapSingleToken(builder, TypstElementTypes.STRING_LITERAL)
                TypstTokenTypes.RAW_TEXT -> wrapSingleToken(builder, TypstElementTypes.RAW)
                TypstTokenTypes.LPAREN ->
                    parseGroup(builder, TypstTokenTypes.RPAREN, TypstElementTypes.PAREN_GROUP)
                TypstTokenTypes.LBRACE ->
                    parseGroup(builder, TypstTokenTypes.RBRACE, TypstElementTypes.CODE_BLOCK)
                TypstTokenTypes.LBRACKET ->
                    parseGroup(builder, TypstTokenTypes.RBRACKET, TypstElementTypes.CONTENT_BLOCK)
                TokenType.BAD_CHARACTER -> consumeErrorToken(builder, "Unexpected character")
                else -> builder.advanceLexer() // math symbol / identifier / operator / TEXT
            }
        }
        marker.done(TypstElementTypes.MATH)
    }

    // ---- helpers ----

    private fun wrapSingleToken(builder: PsiBuilder, type: IElementType) {
        val marker = builder.mark()
        builder.advanceLexer()
        marker.done(type)
    }

    private fun consumeErrorToken(builder: PsiBuilder, message: String) {
        val marker = builder.mark()
        builder.advanceLexer()
        marker.error(message)
    }

    private fun isCodeBodyStopper(type: IElementType): Boolean =
        type == TypstTokenTypes.TEXT ||
            type == TypstTokenTypes.ESCAPE ||
            type == TypstTokenTypes.AT ||
            type == TypstTokenTypes.DOLLAR

    private fun isCloser(type: IElementType): Boolean =
        type == TypstTokenTypes.RPAREN ||
            type == TypstTokenTypes.RBRACE ||
            type == TypstTokenTypes.RBRACKET

    /**
     * True if the gap of skipped tokens (whitespace / comments) immediately before the current
     * token contains a blank line (>= 2 newlines), i.e. a paragraph break. Mirrors the lexer's own
     * blank-line recovery so an unclosed math region cannot span paragraphs.
     */
    private fun hasBlankLineBefore(builder: PsiBuilder): Boolean {
        val currentOffset = builder.currentOffset
        var spanStart = currentOffset
        var step = -1
        while (true) {
            val raw = builder.rawLookup(step) ?: break
            if (raw == TokenType.WHITE_SPACE ||
                raw == TypstTokenTypes.LINE_COMMENT ||
                raw == TypstTokenTypes.BLOCK_COMMENT
            ) {
                spanStart = builder.rawTokenTypeStart(step)
                step--
            } else {
                break
            }
        }
        if (spanStart >= currentOffset) return false
        val gap = builder.originalText
        var newlineCount = 0
        var index = spanStart
        while (index < currentOffset) {
            if (gap[index] == '\n') {
                newlineCount++
                if (newlineCount >= 2) return true
            }
            index++
        }
        return false
    }

    private companion object {
        /**
         * Cap on recursive-descent nesting. Beyond this, bracket-group parsers stop descending and
         * let the enclosing loop consume the remaining tokens flatly, so deeply nested unbalanced
         * input (`[[[[...`, `#(((((...`) yields a valid, bounded tree instead of a StackOverflow.
         */
        private const val MAX_RECURSION_DEPTH = 256
    }
}
