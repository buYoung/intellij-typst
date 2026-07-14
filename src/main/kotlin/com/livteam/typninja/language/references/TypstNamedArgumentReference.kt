package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
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

    override fun handleElementRename(newElementName: String): PsiElement {
        TypstElementFactory.requireIdentifier(newElementName)
        return element.replaceArgumentName(newElementName)
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
