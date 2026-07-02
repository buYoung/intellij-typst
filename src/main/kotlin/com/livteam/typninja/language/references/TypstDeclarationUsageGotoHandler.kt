package com.livteam.typninja.language.references

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstDefinition
import com.livteam.typninja.language.psi.TypstFile

class TypstDeclarationUsageGotoHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val file = sourceElement?.containingFile as? TypstFile ?: return null
        val definition = definitionAt(file, offset) ?: return null
        val usages = ArrayList<PsiElement>()
        collectReferences(file, definition.nameElement, usages)
        if (usages.isEmpty()) return null
        return usages.toTypedArray()
    }

    private fun definitionAt(file: PsiFile, offset: Int): TypstDefinition? {
        val snapshot = TypstAnalysis.snapshot(file) ?: return null
        return snapshot.declarations.firstOrNull { it.nameElement.textRange.containsOffset(offset) }
    }

    private fun collectReferences(element: PsiElement, target: PsiElement, usages: MutableList<PsiElement>) {
        ProgressManager.checkCanceled()
        for (reference in element.references) {
            if (reference.isReferenceTo(target)) usages.add(reference.element)
        }
        for (child in element.children) collectReferences(child, target, usages)
    }
}
