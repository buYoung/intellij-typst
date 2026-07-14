package com.livteam.typninja.language.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.psi.TypstFile

class TypstIncreaseHeadingLevelIntention : IntentionAction {
    override fun getText(): String = "Increase Typst heading level"
    override fun getFamilyName(): String = "Typst headings"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file is TypstFile && TypstHeadingActions.headingMarkerRange(editor)?.length in 1..5

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is TypstFile) return
        val range = TypstHeadingActions.headingMarkerRange(editor) ?: return
        WriteCommandAction.runWriteCommandAction(project, text, null, Runnable {
            editor.document.insertString(range.startOffset, "=")
        }, file)
    }

    override fun startInWriteAction(): Boolean = false
}

class TypstDecreaseHeadingLevelIntention : IntentionAction {
    override fun getText(): String = "Decrease Typst heading level"
    override fun getFamilyName(): String = "Typst headings"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file is TypstFile && (TypstHeadingActions.headingMarkerRange(editor)?.length ?: 0) > 1

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is TypstFile) return
        val range = TypstHeadingActions.headingMarkerRange(editor) ?: return
        if (range.length <= 1) return
        WriteCommandAction.runWriteCommandAction(project, text, null, Runnable {
            editor.document.deleteString(range.startOffset, range.startOffset + 1)
        }, file)
    }

    override fun startInWriteAction(): Boolean = false
}

private object TypstHeadingActions {
    fun headingMarkerRange(editor: Editor): TextRange? {
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset.coerceAtMost(document.textLength))
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val lineText = document.charsSequence.subSequence(start, end).toString()
        val match = Regex("^(\\s*)(={1,6})(?=\\s)").find(lineText) ?: return null
        return TextRange(start + match.range.first + match.groupValues[1].length, start + match.range.first + match.groupValues[1].length + match.groupValues[2].length)
    }
}
