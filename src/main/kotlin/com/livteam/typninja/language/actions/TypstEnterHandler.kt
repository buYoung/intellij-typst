package com.livteam.typninja.language.actions

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.psi.TypstFile

class TypstEnterHandler : EnterHandlerDelegate {

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        if (file !is TypstFile) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val caretOffset = editor.caretModel.offset
        val line = document.getLineNumber(caretOffset)
        if (line == 0) return EnterHandlerDelegate.Result.Continue
        val previousStart = document.getLineStartOffset(line - 1)
        val previousEnd = document.getLineEndOffset(line - 1)
        val previousText = document.text.substring(previousStart, previousEnd)
        val commentPrefixIndex = previousText.indexOf("//")
        if (commentPrefixIndex < 0 || previousText.substring(0, commentPrefixIndex).isNotBlank()) {
            return EnterHandlerDelegate.Result.Continue
        }
        document.insertString(caretOffset, previousText.substring(0, commentPrefixIndex) + "// ")
        editor.caretModel.moveToOffset(caretOffset + commentPrefixIndex + 3)
        return EnterHandlerDelegate.Result.Stop
    }
}
