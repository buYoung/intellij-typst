package com.livteam.typninja.language.references

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.livteam.typninja.language.psi.TypstImportItem
import com.livteam.typninja.language.psi.TypstLabelDefinition
import com.livteam.typninja.language.psi.TypstLetBinding
import com.livteam.typninja.language.psi.TypstModuleImport

class TypstRefactoringSupportProvider : RefactoringSupportProvider() {

    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean {
        if (DumbService.isDumb(element.project) || !isWritableProjectSource(element)) return false
        return when (element) {
            is TypstLetBinding, is TypstLabelDefinition -> true
            is TypstImportItem -> element.nameIdentifier != null && !element.text.trimEnd().endsWith('.')
            is TypstModuleImport -> element.nameIdentifier != null
            else -> false
        }
    }

    private fun isWritableProjectSource(element: PsiElement): Boolean {
        val file = element.containingFile?.virtualFile ?: return false
        return file.isWritable && ProjectFileIndex.getInstance(element.project).isInContent(file)
    }
}
