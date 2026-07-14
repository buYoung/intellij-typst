package com.livteam.typninja.language.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.PsiElement

/** A small, undoable editor fix attached to a Typst diagnostic. */
class TypstTextEditIntention(
    text: String,
    anchor: PsiElement,
    private val editRange: TextRange,
    private val replacement: String,
) : IntentionAction {
    private val actionText = text
    private val anchorPointer: SmartPsiElementPointer<PsiElement> =
        SmartPointerManager.getInstance(anchor.project).createSmartPsiElementPointer(anchor)

    override fun getText(): String = actionText
    override fun getFamilyName(): String = "Typst"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val anchor = anchorPointer.element ?: return false
        return file != null && anchor.containingFile == file && editRange.endOffset <= file.textLength
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = editor?.document ?: file?.viewProvider?.document ?: return
        if (editRange.endOffset > document.textLength) return
        WriteCommandAction.runWriteCommandAction(project, actionText, null, {
            document.replaceString(editRange.startOffset, editRange.endOffset, replacement)
            editor?.caretModel?.moveToOffset(editRange.startOffset + replacement.length)
        }, file)
    }

    override fun startInWriteAction(): Boolean = false
}
