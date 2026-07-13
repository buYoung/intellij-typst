package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.references.TypstFieldMemberReference

class TypstFieldAccess(node: ASTNode) : TypstPsiElement(node) {

    val memberName: String?
        get() = memberNode()?.text

    val qualifierName: String?
        get() {
            var child = node.firstChildNode
            while (child != null) {
                if (child.elementType == TypstElementTypes.REFERENCE_EXPR) {
                    return (child.psi as? TypstReferenceExpression)?.referenceName
                }
                if (child.elementType == TypstElementTypes.FIELD_ACCESS) {
                    return (child.psi as? TypstFieldAccess)?.qualifierName
                }
                child = child.treeNext
            }
            return null
        }

    override fun getReference(): PsiReference? {
        val member = memberNode() ?: return null
        // A generic `value.field` is dynamic Typst data and cannot safely promise navigation.
        // Install a reference only for a statically known imported module namespace.
        if (TypstAnalysis.fieldDefinitions(this).none { it.name == member.text }) return null
        val start = member.startOffset - node.startOffset
        return TypstFieldMemberReference(this, TextRange(start, start + member.textLength))
    }

    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY

    fun replaceMemberName(name: String): PsiElement {
        val member = memberNode() ?: return this
        member.psi.replace(TypstElementFactory.createIdentifier(project, name))
        return this
    }

    private fun memberNode(): ASTNode? {
        var result: ASTNode? = null
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType == TypstTokenTypes.IDENTIFIER) result = child
            child = child.treeNext
        }
        return result
    }
}
