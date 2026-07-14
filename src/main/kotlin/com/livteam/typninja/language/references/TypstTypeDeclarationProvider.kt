package com.livteam.typninja.language.references

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.livteam.typninja.language.analysis.TypstTypeInference

class TypstTypeDeclarationProvider : TypeDeclarationProvider, DumbAware {

    override fun getSymbolTypeDeclarations(symbol: PsiElement): Array<PsiElement> {
        val typeName = TypstTypeInference.typeName(symbol) ?: return PsiElement.EMPTY_ARRAY
        val target = TypstBuiltinResolver.resolve(symbol.project, typeName) ?: return PsiElement.EMPTY_ARRAY
        return arrayOf(target)
    }
}
