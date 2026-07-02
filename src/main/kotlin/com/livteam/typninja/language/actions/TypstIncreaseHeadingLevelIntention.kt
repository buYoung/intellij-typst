package com.livteam.typninja.language.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstTokenTypes

class TypstIncreaseHeadingLevelIntention : IntentionAction {

    override fun getText(): String = "Increase Typst heading level"

    override fun getFamilyName(): String = "Typst headings"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is TypstFile) return false
        val leaf = file.findElementAt(editor.caretModel.offset) ?: return false
        return leaf.node.elementType == TypstTokenTypes.HEADING_MARKER
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is TypstFile) return
        val leaf = file.findElementAt(editor.caretModel.offset) ?: return
        if (leaf.node.elementType != TypstTokenTypes.HEADING_MARKER) return
        WriteCommandAction.runWriteCommandAction(project, text, null, Runnable {
            editor.document.insertString(leaf.textRange.startOffset, "=")
        }, file)
    }

    override fun startInWriteAction(): Boolean = false
}
