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

        /**
         * Paren-delimited group nodes (`( … )`): arguments, arrays, dictionaries, parameter lists,
         * parenthesized expressions and destructuring patterns. The P3 grammar produces these finer
         * nodes in place of the removed legacy `PAREN_GROUP`; they all format identically (structural
         * indent when closed, verbatim leaf when unclosed).
         */
        private val PAREN_GROUPS: Set<IElementType> = setOf(
            TypstElementTypes.ARGS,
            TypstElementTypes.ARRAY,
            TypstElementTypes.DICT,
            TypstElementTypes.PARAMS,
            TypstElementTypes.PARENTHESIZED,
            TypstElementTypes.DESTRUCTURING,
        )

        /**
         * Non-brace code wrappers: `#`-expressions, function calls, closures, statements and
         * destructuring assignments. They recurse (so nested closed groups/blocks get tidied and the
         * spacing rules in [TypstSpacingBuilder] normalise `=` / `:` / `,` between their direct
         * children — e.g. `#let a=1` → `#let a = 1`), but add no structural indent of their own, so a
         * statement's own tokens stay at the parent column. Any markup they contain lives inside a
         * [TypstElementTypes.CONTENT_BLOCK] (a preserve container), so prose is never rewritten.
         */
        private val CODE_WRAPPERS: Set<IElementType> = setOf(
            TypstElementTypes.CODE_EXPRESSION,
            TypstElementTypes.FUNC_CALL,
            TypstElementTypes.FIELD_ACCESS,
            TypstElementTypes.CLOSURE,
            TypstElementTypes.UNARY,
            TypstElementTypes.BINARY,
            TypstElementTypes.LET_BINDING,
            TypstElementTypes.SET_RULE,
            TypstElementTypes.SHOW_RULE,
            TypstElementTypes.CONTEXTUAL,
            TypstElementTypes.CONDITIONAL,
            TypstElementTypes.WHILE_LOOP,
            TypstElementTypes.FOR_LOOP,
            TypstElementTypes.MODULE_IMPORT,
            TypstElementTypes.IMPORT_ITEMS,
            TypstElementTypes.IMPORT_ITEM,
            TypstElementTypes.MODULE_INCLUDE,
            TypstElementTypes.LOOP_BREAK,
            TypstElementTypes.LOOP_CONTINUE,
            TypstElementTypes.FUNC_RETURN,
            TypstElementTypes.DESTRUCT_ASSIGNMENT,
            TypstElementTypes.NAMED,
            TypstElementTypes.KEYED,
            TypstElementTypes.SPREAD,
            TypstElementTypes.BINDING_DECLARATION,
        )

        private fun computeKind(node: ASTNode): Kind {
            if (node.elementType is IFileElementType) return Kind.PRESERVE_CONTAINER
            val type = node.elementType
            return when {
                type == TypstElementTypes.MATH ||
                    type == TypstElementTypes.CONTENT_BLOCK -> Kind.PRESERVE_CONTAINER

                // Code wrappers/statements that must recurse so their nested groups get tidied and
                // their inter-child spacing gets normalised, but that add no structural indent
                // themselves (they are not brace-like).
                type in CODE_WRAPPERS -> Kind.CODE_CONTAINER

                type in PAREN_GROUPS ->
                    if (isClosed(node, TypstTokenTypes.RPAREN)) Kind.CODE_CONTAINER else Kind.LEAF

                type == TypstElementTypes.CODE_BLOCK ->
                    if (isClosed(node, TypstTokenTypes.RBRACE)) Kind.CODE_CONTAINER else Kind.LEAF

                // Markup prose, atomic expressions, token leaves and error elements are preserved
                // verbatim. Code wrappers above recurse so punctuation can be spaced by context.
                else -> Kind.LEAF
            }
        }

        private fun isBraceLikeGroup(type: IElementType): Boolean =
            type in PAREN_GROUPS || type == TypstElementTypes.CODE_BLOCK

        private fun isOpener(parentType: IElementType, childType: IElementType): Boolean = when {
            parentType in PAREN_GROUPS -> childType == TypstTokenTypes.LPAREN
            parentType == TypstElementTypes.CODE_BLOCK -> childType == TypstTokenTypes.LBRACE
            else -> false
        }

        private fun isCloser(parentType: IElementType, childType: IElementType): Boolean = when {
            parentType in PAREN_GROUPS -> childType == TypstTokenTypes.RPAREN
            parentType == TypstElementTypes.CODE_BLOCK -> childType == TypstTokenTypes.RBRACE
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
