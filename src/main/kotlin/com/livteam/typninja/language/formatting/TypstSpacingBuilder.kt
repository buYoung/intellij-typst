package com.livteam.typninja.language.formatting

import com.intellij.formatting.Block
import com.intellij.formatting.Spacing
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.TypstLanguage
import com.livteam.typninja.language.psi.TypstElementTypes
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
        val parentType = parent.node.elementType

        // Comment-group preservation (FDD 9.7): never reflow or merge across a comment.
        if (isComment(left) || isComment(right)) return Spacing.getReadOnlySpacing()

        // Tokens that form one expression never get separated.
        if (parentType == TypstElementTypes.CODE_EXPRESSION && left == TypstTokenTypes.HASH) return beforeTight
        if (parentType == TypstElementTypes.FIELD_ACCESS &&
            (left == TypstTokenTypes.DOT || right == TypstTokenTypes.DOT)
        ) return beforeTight
        if (parentType == TypstElementTypes.IMPORT_ITEM &&
            (left == TypstTokenTypes.DOT || right == TypstTokenTypes.DOT)
        ) return beforeTight
        if (parentType == TypstElementTypes.SPREAD && left == TypstTokenTypes.DOT_DOT) return beforeTight
        if (parentType == TypstElementTypes.UNARY && isUnaryOperator(left)) return beforeTight
        if (parentType == TypstElementTypes.FUNC_CALL &&
            (right == TypstElementTypes.ARGS || right == TypstElementTypes.CONTENT_BLOCK)
        ) return beforeTight

        // Delimiters have no padding on a single line. Existing line breaks are retained.
        if (isOpener(left) || isCloser(right)) return beforeTight

        // Separators.
        if (isSeparator(right)) return beforeTight
        if (isSeparator(left)) return afterSingle

        // Operators are spaced only inside a parsed binary expression. This avoids changing unary
        // signs, field access, spreads, markup punctuation and other visually similar tokens.
        if ((parentType == TypstElementTypes.BINARY || parentType == TypstElementTypes.LET_BINDING) &&
            (isBinaryOperator(left) || isBinaryOperator(right))
        ) return afterSingle

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

        private val OPENERS = setOf(
            TypstTokenTypes.LPAREN,
            TypstTokenTypes.LBRACE,
            TypstTokenTypes.LBRACKET,
        )

        private val BINARY_OPERATORS = setOf(
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
            TypstTokenTypes.ARROW,
            TypstTokenTypes.KW_IN,
            TypstTokenTypes.KW_AND,
            TypstTokenTypes.KW_OR,
            TypstTokenTypes.KW_NOT,
        )

        private val UNARY_OPERATORS = setOf(
            TypstTokenTypes.PLUS,
            TypstTokenTypes.MINUS,
            TypstTokenTypes.KW_NOT,
        )

        private val COMMENTS = setOf(
            TypstTokenTypes.LINE_COMMENT,
            TypstTokenTypes.BLOCK_COMMENT,
        )

        private fun isSeparator(type: IElementType): Boolean = type in SEPARATORS
        private fun isOpener(type: IElementType): Boolean = type in OPENERS
        private fun isCloser(type: IElementType): Boolean = type in CLOSERS
        private fun isBinaryOperator(type: IElementType): Boolean = type in BINARY_OPERATORS
        private fun isUnaryOperator(type: IElementType): Boolean = type in UNARY_OPERATORS
        private fun isComment(type: IElementType): Boolean = type in COMMENTS
    }
}
