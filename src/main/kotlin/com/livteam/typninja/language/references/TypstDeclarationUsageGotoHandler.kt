package com.livteam.typninja.language.references

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
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
        TypstAnalysis.snapshot(file)?.referenceCandidates?.get(definition.name)?.forEach { candidate ->
            candidate.references.filter { it.isReferenceTo(definition.nameElement) }.forEach { usages.add(it.element) }
        }
        if (usages.isEmpty()) return null
        return usages.toTypedArray()
    }

    private fun definitionAt(file: PsiFile, offset: Int): TypstDefinition? {
        val snapshot = TypstAnalysis.snapshot(file) ?: return null
        return snapshot.declarations.firstOrNull { it.nameElement.textRange.containsOffset(offset) }
    }

}
