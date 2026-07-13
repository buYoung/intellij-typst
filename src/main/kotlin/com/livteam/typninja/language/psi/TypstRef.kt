package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.livteam.typninja.language.references.TypstLabelReference
import com.livteam.typninja.language.references.TypstLabelResolver

/**
 * PSI for a `@reference` markup node ([TypstElementTypes.REF]).
 *
 * The node wraps a [TypstTokenTypes.REF_MARKER] leaf (`@name`) and, optionally, a trailing
 * `[ ... ]` content-supplement block. It carries a [TypstLabelReference] over the `@name` marker so
 * Cmd+Click navigates to the `<name>` label definition in the same file.
 */
class TypstRef(node: ASTNode) : TypstPsiElement(node) {

    /** The referenced label name (the marker text without the leading `@`). */
    val referenceName: String
        get() = markerNode()?.let { TypstLabelResolver.referenceName(it.text) } ?: ""

    override fun getReference(): PsiReference? {
        val marker = markerNode() ?: return null
        val start = marker.startOffset - node.startOffset
        return TypstLabelReference(this, TextRange(start, start + marker.textLength))
    }

    override fun getReferences(): Array<PsiReference> =
        getReference()?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY

    private fun markerNode(): ASTNode? = node.findChildByType(TypstTokenTypes.REF_MARKER)

    fun renamed(name: String): PsiElement = replace(TypstElementFactory.createLabelReference(project, name))
}
