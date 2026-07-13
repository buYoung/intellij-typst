package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.psi.TypstElementFactory
import com.livteam.typninja.language.psi.TypstFieldAccess

class TypstFieldMemberReference(element: TypstFieldAccess, rangeInElement: TextRange) :
    PsiReferenceBase<TypstFieldAccess>(element, rangeInElement) {

    override fun resolve(): PsiElement? = TypstAnalysis.resolveField(element)?.navigationElement

    override fun getVariants(): Array<Any> = TypstAnalysis.fieldDefinitions(element)
        .map { it.name }
        .distinct()
        .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        TypstElementFactory.requireIdentifier(newElementName)
        return element.replaceMemberName(newElementName)
    }
}
