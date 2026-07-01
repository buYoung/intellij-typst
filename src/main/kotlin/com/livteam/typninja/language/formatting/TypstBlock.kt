package com.livteam.typninja.language.formatting

import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * One node in the Typst formatting block tree.
 *
 * Conservatism is encoded in [Kind]:
 *  - [Kind.LEAF] — opaque region whose text is NEVER reformatted internally. Markup prose, raw text,
 *    strings, labels, error elements, comments, and any *unclosed* group are leaves, so their inner
 *    whitespace and newlines are preserved by construction.
 *  - [Kind.PRESERVE_CONTAINER] — the file root, math, and content blocks. Children are still visited
 *    (so a nested *closed* code group can be tidied), but spacing between the container's direct
 *    children is read-only ([TypstSpacingBuilder]) and their indent is none, so prose/math layout is
 *    preserved.
 *  - [Kind.CODE_CONTAINER] — a `#`-expression, or a *closed* `( )` / `{ }` group. Here spacing rules
 *    and structural indentation apply: body children of a closed brace-like group get one indent
 *    level, the opener and closer stay at the parent level.
 *
 * Indentation is expressed with [Indent.getNormalIndent] / [Indent.getNoneIndent] only — no manual
 * column math and no [com.intellij.formatting.Alignment] — so repeated formatting converges.
 */
class TypstBlock(
    node: ASTNode,
    private val blockIndent: Indent,
    private val spacingBuilder: TypstSpacingBuilder,
) : AbstractBlock(node, null, null) {

    enum class Kind { LEAF, PRESERVE_CONTAINER, CODE_CONTAINER }

    val kind: Kind = computeKind(node)

    /** Spacing normalization (commas/operators) and child indentation only apply here. */
    val isCodeContainer: Boolean get() = kind == Kind.CODE_CONTAINER

    override fun getIndent(): Indent = blockIndent

    override fun isLeaf(): Boolean = kind == Kind.LEAF

    override fun buildChildren(): List<Block> {
        if (kind == Kind.LEAF) return emptyList()
        val blocks = ArrayList<Block>()
        var child = myNode.firstChildNode
        while (child != null) {
            if (shouldCreateBlock(child)) {
                blocks.add(TypstBlock(child, childIndentFor(child), spacingBuilder))
            }
            child = child.treeNext
        }
        return blocks
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? =
        spacingBuilder.getSpacing(this, child1, child2)

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val indent =
            if (kind == Kind.CODE_CONTAINER && isBraceLikeGroup(myNode.elementType)) Indent.getNormalIndent()
            else Indent.getNoneIndent()
        return ChildAttributes(indent, null)
    }

    private fun shouldCreateBlock(child: ASTNode): Boolean =
        child.elementType != TokenType.WHITE_SPACE && child.textRange.length > 0

    private fun childIndentFor(child: ASTNode): Indent {
        if (kind != Kind.CODE_CONTAINER) return Indent.getNoneIndent()
        val parentType = myNode.elementType
        if (!isBraceLikeGroup(parentType)) return Indent.getNoneIndent() // CODE_EXPRESSION body stays flat
        val childType = child.elementType
        return if (isOpener(parentType, childType) || isCloser(parentType, childType)) {
            Indent.getNoneIndent()
        } else {
            Indent.getNormalIndent()
        }
    }

    private companion object {

        private fun computeKind(node: ASTNode): Kind {
            if (node.elementType is IFileElementType) return Kind.PRESERVE_CONTAINER
            return when (node.elementType) {
                TypstElementTypes.MARKUP,
                TypstElementTypes.RAW,
                TypstElementTypes.STRING_LITERAL,
                TypstElementTypes.LABEL,
                -> Kind.LEAF

                TypstElementTypes.MATH,
                TypstElementTypes.CONTENT_BLOCK,
                -> Kind.PRESERVE_CONTAINER

                TypstElementTypes.CODE_EXPRESSION -> Kind.CODE_CONTAINER

                TypstElementTypes.PAREN_GROUP ->
                    if (isClosed(node, TypstTokenTypes.RPAREN)) Kind.CODE_CONTAINER else Kind.LEAF

                TypstElementTypes.CODE_BLOCK ->
                    if (isClosed(node, TypstTokenTypes.RBRACE)) Kind.CODE_CONTAINER else Kind.LEAF

                // Token leaves (identifiers, keywords, operators, comments) and error elements.
                else -> Kind.LEAF
            }
        }

        private fun isBraceLikeGroup(type: IElementType): Boolean =
            type == TypstElementTypes.PAREN_GROUP || type == TypstElementTypes.CODE_BLOCK

        private fun isOpener(parentType: IElementType, childType: IElementType): Boolean = when (parentType) {
            TypstElementTypes.PAREN_GROUP -> childType == TypstTokenTypes.LPAREN
            TypstElementTypes.CODE_BLOCK -> childType == TypstTokenTypes.LBRACE
            else -> false
        }

        private fun isCloser(parentType: IElementType, childType: IElementType): Boolean = when (parentType) {
            TypstElementTypes.PAREN_GROUP -> childType == TypstTokenTypes.RPAREN
            TypstElementTypes.CODE_BLOCK -> childType == TypstTokenTypes.RBRACE
            else -> false
        }

        /** A group is "closed" when its last meaningful child is the matching closing delimiter. */
        private fun isClosed(node: ASTNode, closer: IElementType): Boolean {
            var c = node.lastChildNode
            while (c != null && c.elementType == TokenType.WHITE_SPACE) c = c.treePrev
            return c != null && c.elementType == closer
        }
    }
}
