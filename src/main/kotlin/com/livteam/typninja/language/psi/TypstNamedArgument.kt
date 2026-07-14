package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiNameIdentifierOwner
import com.livteam.typninja.language.references.TypstNamedArgumentReference

/** A named call argument such as `align: center`. */
class TypstNamedArgument(node: ASTNode) : TypstPsiElement(node), PsiNameIdentifierOwner {

    val argumentName: String?
        get() = nameNode()?.text

    override fun getNameIdentifier(): PsiElement? = nameNode()?.psi

    override fun getName(): String? = argumentName

    override fun setName(name: String): PsiElement = replaceArgumentName(name)

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getReference(): PsiReference? {
        if (node.treeParent?.elementType != TypstElementTypes.ARGS) return null
        val nameNode = nameNode() ?: return null
        val start = nameNode.startOffset - node.startOffset
        return TypstNamedArgumentReference(this, TextRange(start, start + nameNode.textLength))
    }

    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY

    fun replaceArgumentName(name: String): PsiElement {
        val currentName = nameNode() ?: return this
        currentName.psi.replace(TypstElementFactory.createIdentifier(project, name))
        return this
    }

    private fun nameNode(): ASTNode? {
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType == TypstTokenTypes.IDENTIFIER) return child
            child = child.treeNext
        }
        return null
    }
}
