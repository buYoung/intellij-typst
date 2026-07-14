package com.livteam.typninja.language.editor

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.psi.TypstFile

/** Opens IntelliJ completion at the Typst characters that introduce a new choice. */
class TypstTypedHandler : TypedHandlerDelegate(), DumbAware {
    override fun checkAutoPopup(
        charTyped: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
    ): Result {
        if (file !is TypstFile || charTyped !in AUTO_POPUP_CHARACTERS) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }

    private companion object {
        val AUTO_POPUP_CHARACTERS = setOf('.', '@', '#', ':', '/')
    }
}
