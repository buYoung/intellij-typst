package com.livteam.typninja.language.formatting

import com.intellij.formatting.Block
import com.intellij.formatting.Spacing
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.TypstLanguage
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * Conservative spacing rules for the Typst formatter.
 *
 * The decisive preservation primitive is [Spacing.getReadOnlySpacing]: anywhere outside a
 * code-structural region (or anywhere we cannot classify the boundary) the original whitespace is
 * marked read-only, so the engine never touches markup prose, math layout, comment groups, or the
 * indentation the author chose.
 *
 * Inside a code-structural region ([TypstBlock.isCodeContainer]) only three normalizations fire, all
 * idempotent and all matching the brief's scope (COMMA / COLON / binary operators):
 *  - no space before `,` `:` `;`           -> [beforeTight]
 *  - exactly one space after `,` `:` `;`    -> [afterSingle]
 *  - exactly one space around binary/compare/assignment operators -> [afterSingle]
 *
 * Every active rule keeps existing line breaks (so multi-line structures stay multi-line and only get
 * re-indented via the block [com.intellij.formatting.Indent]s), and every same-line decision is a
 * fixed count (0 or 1), so re-running the formatter produces no further diff. Opener/closer adjacency
 * and all other code boundaries fall through to [structural], which preserves same-line spaces and
 * only re-indents across kept line breaks — it never normalizes inner padding.
 */
class TypstSpacingBuilder(settings: CodeStyleSettings) {

    private val keepBlankLines: Int =
        settings.getCommonSettings(TypstLanguage).KEEP_BLANK_LINES_IN_CODE.coerceAtLeast(0)

    /** No spaces, but keep line breaks (used before `,` `:` `;`). */
    private val beforeTight: Spacing = Spacing.createSpacing(0, 0, 0, true, keepBlankLines)

    /** Exactly one space, but keep line breaks (after `,` `:` `;` and around binary operators). */
    private val afterSingle: Spacing = Spacing.createSpacing(1, 1, 0, true, keepBlankLines)

    /** Preserve same-line spacing; keep line breaks so indentation can be re-applied. Default. */
    private val structural: Spacing = Spacing.createSpacing(0, Int.MAX_VALUE, 0, true, keepBlankLines)

    fun getSpacing(parent: TypstBlock, child1: Block?, child2: Block): Spacing? {
        // Outside a code-structural region everything is preserved verbatim (markup, math, content
        // blocks, unclosed groups handled as leaves elsewhere).
        if (!parent.isCodeContainer) return Spacing.getReadOnlySpacing()
        if (child1 == null) return null
        if (child1 !is TypstBlock || child2 !is TypstBlock) return Spacing.getReadOnlySpacing()

        val left: IElementType = child1.node.elementType
        val right: IElementType = child2.node.elementType

        // Comment-group preservation (FDD 9.7): never reflow or merge across a comment.
        if (isComment(left) || isComment(right)) return Spacing.getReadOnlySpacing()

        // Before a closer: preserve (handles trailing commas and author padding); only re-indent.
        if (isCloser(right)) return structural

        // Separators.
        if (isSeparator(right)) return beforeTight
        if (isSeparator(left)) return afterSingle

        // Unambiguous binary / comparison / assignment operators get one space on each side.
        if (isBinaryOperator(left) || isBinaryOperator(right)) return afterSingle

        return structural
    }

    private companion object {
        private val SEPARATORS = setOf(
            TypstTokenTypes.COMMA,
            TypstTokenTypes.COLON,
            TypstTokenTypes.SEMICOLON,
        )

        private val CLOSERS = setOf(
            TypstTokenTypes.RPAREN,
            TypstTokenTypes.RBRACE,
            TypstTokenTypes.RBRACKET,
        )

        // Arithmetic (+ - * /), DOT, DOT_DOT and HAT are intentionally excluded: they can be unary,
        // markup-derived, kebab-identifier, field-access or spread, so spacing them is unsafe.
        private val BINARY_OPERATORS = setOf(
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
            TypstTokenTypes.ARROW,
        )

        private val COMMENTS = setOf(
            TypstTokenTypes.LINE_COMMENT,
            TypstTokenTypes.BLOCK_COMMENT,
        )

        private fun isSeparator(type: IElementType): Boolean = type in SEPARATORS
        private fun isCloser(type: IElementType): Boolean = type in CLOSERS
        private fun isBinaryOperator(type: IElementType): Boolean = type in BINARY_OPERATORS
        private fun isComment(type: IElementType): Boolean = type in COMMENTS
    }
}
