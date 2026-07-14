package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.psi.TypstElementFactory
import com.livteam.typninja.language.psi.TypstFieldAccess

class TypstFieldMemberReference(element: TypstFieldAccess, rangeInElement: TextRange) :
    PsiReferenceBase<TypstFieldAccess>(element, rangeInElement) {

    override fun resolve(): PsiElement? = TypstAnalysis.resolveField(element)?.navigationElement

    override fun isReferenceTo(candidate: PsiElement): Boolean {
        val resolved = resolve() ?: return false
        if (resolved.manager.areElementsEquivalent(resolved, candidate)) return true
        val resolvedOwner = PsiTreeUtil.getParentOfType(resolved, PsiNameIdentifierOwner::class.java, false)
        if (resolvedOwner != null && resolved.manager.areElementsEquivalent(resolvedOwner, candidate)) return true
        val candidateIdentifier = (candidate as? PsiNameIdentifierOwner)?.nameIdentifier
        return candidateIdentifier != null && resolved.manager.areElementsEquivalent(resolved, candidateIdentifier)
    }

    override fun getVariants(): Array<Any> = TypstAnalysis.fieldDefinitions(element)
        .map { it.name }
        .distinct()
        .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        TypstElementFactory.requireIdentifier(newElementName)
        return element.replaceMemberName(newElementName)
    }
}
