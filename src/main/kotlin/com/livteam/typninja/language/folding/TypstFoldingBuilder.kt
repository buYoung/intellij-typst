package com.livteam.typninja.language.folding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * Folds closed, multi-line Typst groups: `{ ... }` code blocks, `( ... )` groups (args / arrays /
 * dictionaries) and `[ ... ]` content blocks.
 *
 * A region is only offered when the group is *closed* — its last meaningful child is the matching
 * closing delimiter (the same conservative "closed" test the formatter uses) — and spans more than
 * one line. Unclosed or single-line groups produce no fold, mirroring the recovery-first policy: an
 * incomplete document is never made harder to read by a fold over a half-open range.
 *
 * Nested groups fold independently because the walk descends into every child.
 *
 * Stateless and [DumbAware]; registered via `<lang.foldingBuilder language="Typst" .../>`.
 */
class TypstFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = ArrayList<FoldingDescriptor>()
        collect(root.node, document, descriptors)
        collectHeadingSections(root.node, document, descriptors)
        collectImportGroups(root.node, document, descriptors)
        return descriptors.toTypedArray()
    }

    private fun collect(node: ASTNode?, document: Document, out: MutableList<FoldingDescriptor>) {
        if (node == null) return
        if (isFoldable(node, document)) {
            out.add(FoldingDescriptor(node, node.textRange))
        }
        var child = node.firstChildNode
        while (child != null) {
            collect(child, document, out)
            child = child.treeNext
        }
    }

    override fun getPlaceholderText(node: ASTNode): String = when (node.elementType) {
        TypstElementTypes.CODE_BLOCK -> "{...}"
        TypstElementTypes.CONTENT_BLOCK -> "[...]"
        TypstElementTypes.HEADING -> " section..."
        TypstElementTypes.MODULE_IMPORT -> "imports..."
        TypstTokenTypes.BLOCK_COMMENT -> "/*...*/"
        TypstTokenTypes.RAW_TEXT -> "`...`"
        in PAREN_GROUPS -> "(...)"
        else -> "..."
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private companion object {

        /** Paren-delimited group nodes produced by the P3 grammar (args / arrays / dicts / params). */
        private val PAREN_GROUPS: Set<IElementType> = setOf(
            TypstElementTypes.ARGS,
            TypstElementTypes.ARRAY,
            TypstElementTypes.DICT,
            TypstElementTypes.PARAMS,
            TypstElementTypes.PARENTHESIZED,
            TypstElementTypes.DESTRUCTURING,
        )

        private fun isFoldable(node: ASTNode, document: Document): Boolean {
            if (node.elementType == TypstTokenTypes.BLOCK_COMMENT || node.elementType == TypstTokenTypes.RAW_TEXT) {
                val range = node.textRange
                return !range.isEmpty && range.endOffset <= document.textLength &&
                    document.getLineNumber(range.startOffset) < document.getLineNumber(range.endOffset)
            }
            val closer = closerFor(node.elementType) ?: return false
            if (!isClosed(node, closer)) return false
            val range = node.textRange
            if (range.isEmpty) return false
            return document.getLineNumber(range.startOffset) < document.getLineNumber(range.endOffset)
        }

        private fun closerFor(type: IElementType): IElementType? = when {
            type == TypstElementTypes.CODE_BLOCK -> TypstTokenTypes.RBRACE
            type == TypstElementTypes.CONTENT_BLOCK -> TypstTokenTypes.RBRACKET
            type in PAREN_GROUPS -> TypstTokenTypes.RPAREN
            else -> null
        }

        /** A group is "closed" when its last meaningful child is the matching closing delimiter. */
        private fun isClosed(node: ASTNode, closer: IElementType): Boolean {
            var c = node.lastChildNode
            while (c != null && c.elementType == TokenType.WHITE_SPACE) c = c.treePrev
            return c != null && c.elementType == closer
        }
    }

    private fun collectHeadingSections(
        root: ASTNode,
        document: Document,
        output: MutableList<FoldingDescriptor>,
    ) {
        val headings = ArrayList<ASTNode>()
        collectNodes(root, TypstElementTypes.HEADING, headings)
        for (index in headings.indices) {
            val heading = headings[index]
            val level = headingLevel(heading)
            val startLine = document.getLineNumber(heading.textRange.endOffset.coerceAtMost(document.textLength)) + 1
            if (startLine >= document.lineCount) continue
            val startOffset = document.getLineStartOffset(startLine)
            val nextHeading = headings.drop(index + 1).firstOrNull { headingLevel(it) <= level }
            val endOffset = nextHeading?.textRange?.startOffset ?: document.textLength
            if (startOffset < endOffset && document.getLineNumber(startOffset) < document.getLineNumber(endOffset)) {
                output.add(FoldingDescriptor(heading, com.intellij.openapi.util.TextRange(startOffset, endOffset)))
            }
        }
    }

    private fun collectImportGroups(
        root: ASTNode,
        document: Document,
        output: MutableList<FoldingDescriptor>,
    ) {
        val imports = ArrayList<ASTNode>()
        collectNodes(root, TypstElementTypes.MODULE_IMPORT, imports)
        var groupStart = 0
        while (groupStart < imports.size) {
            var groupEnd = groupStart
            while (groupEnd + 1 < imports.size) {
                val gap = document.charsSequence.subSequence(imports[groupEnd].textRange.endOffset, imports[groupEnd + 1].textRange.startOffset)
                if (gap.any { !it.isWhitespace() }) break
                groupEnd++
            }
            if (groupEnd > groupStart) {
                val range = com.intellij.openapi.util.TextRange(imports[groupStart].textRange.startOffset, imports[groupEnd].textRange.endOffset)
                output.add(FoldingDescriptor(imports[groupStart], range))
            }
            groupStart = groupEnd + 1
        }
    }

    private fun collectNodes(node: ASTNode?, type: IElementType, output: MutableList<ASTNode>) {
        if (node == null) return
        if (node.elementType == type) output.add(node)
        var child = node.firstChildNode
        while (child != null) {
            collectNodes(child, type, output)
            child = child.treeNext
        }
    }

    private fun headingLevel(heading: ASTNode): Int =
        heading.findChildByType(TypstTokenTypes.HEADING_MARKER)?.textLength ?: Int.MAX_VALUE
}
