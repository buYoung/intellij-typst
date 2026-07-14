package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.analysis.TypstSemanticModel
import com.livteam.typninja.language.psi.TypstElementFactory
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstNamedArgument

class TypstNamedArgumentReference(element: TypstNamedArgument, rangeInElement: TextRange) :
    PsiReferenceBase<TypstNamedArgument>(element, rangeInElement) {

    override fun resolve(): PsiElement? {
        val name = element.argumentName ?: return null
        val call = generateSequence(element.node.treeParent) { it.treeParent }
            .firstOrNull { it.elementType == TypstElementTypes.FUNC_CALL }
            ?: return null
        return TypstSemanticModel.parameterTarget(call, name)
    }

    override fun isReferenceTo(candidate: PsiElement): Boolean {
        val resolved = resolve() ?: return false
        if (resolved.manager.areElementsEquivalent(resolved, candidate)) return true
        val owner = PsiTreeUtil.getParentOfType(resolved, PsiNameIdentifierOwner::class.java, false)
        if (owner != null && resolved.manager.areElementsEquivalent(owner, candidate)) return true
        val candidateIdentifier = (candidate as? PsiNameIdentifierOwner)?.nameIdentifier
        return candidateIdentifier != null && resolved.manager.areElementsEquivalent(resolved, candidateIdentifier)
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        TypstElementFactory.requireIdentifier(newElementName)
        return element.replaceArgumentName(newElementName)
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
