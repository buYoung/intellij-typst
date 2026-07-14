package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.psi.TypstElementFactory
import com.livteam.typninja.language.psi.TypstMathIdentifier

class TypstMathReference(element: TypstMathIdentifier) :
    PsiReferenceBase<TypstMathIdentifier>(element, TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? = TypstAnalysis.resolveMath(element)?.definition?.navigationElement

    override fun isReferenceTo(candidate: PsiElement): Boolean {
        val resolved = resolve() ?: return false
        if (resolved.manager.areElementsEquivalent(resolved, candidate)) return true
        val resolvedOwner = PsiTreeUtil.getParentOfType(resolved, PsiNameIdentifierOwner::class.java, false)
        if (resolvedOwner != null && resolved.manager.areElementsEquivalent(resolvedOwner, candidate)) return true
        val candidateName = (candidate as? PsiNameIdentifierOwner)?.nameIdentifier
        return candidateName != null && resolved.manager.areElementsEquivalent(resolved, candidateName)
    }

    override fun handleElementRename(newElementName: String): PsiElement =
        element.replace(TypstElementFactory.createMathIdentifier(element.project, newElementName))

    override fun getVariants(): Array<Any> = TypstBuiltins.mathMembers().map(TypstBuiltins.Metadata::name).toTypedArray()
}
