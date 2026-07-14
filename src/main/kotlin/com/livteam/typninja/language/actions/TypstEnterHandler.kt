package com.livteam.typninja.language.actions

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.settings.TypstSettingsService

/** Continues Typst comments, markup lists, and multi-line equations after Enter. */
class TypstEnterHandler : EnterHandlerDelegate {

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        if (file !is TypstFile) return EnterHandlerDelegate.Result.Continue
        if (!TypstSettingsService.getInstance(file.project).state.enableOnEnter) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val caretOffset = editor.caretModel.offset
        val lineNumber = document.getLineNumber(caretOffset)
        if (lineNumber == 0) return EnterHandlerDelegate.Result.Continue
        val currentLineStart = document.getLineStartOffset(lineNumber)
        val existingIndent = document.charsSequence.subSequence(currentLineStart, caretOffset).toString()
        if (existingIndent.any { !it.isWhitespace() }) {
            return EnterHandlerDelegate.Result.Continue
        }

        val previousStart = document.getLineStartOffset(lineNumber - 1)
        val previousEnd = document.getLineEndOffset(lineNumber - 1)
        val previousText = document.charsSequence.subSequence(previousStart, previousEnd).toString()
        val continuation = commentContinuation(previousText)
            ?: listContinuation(previousText)
            ?: mathContinuation(document.charsSequence, previousStart, previousText)
            ?: return EnterHandlerDelegate.Result.Continue

        val missingContinuation = continuation.removePrefix(existingIndent)
        document.insertString(caretOffset, missingContinuation)
        editor.caretModel.moveToOffset(caretOffset + missingContinuation.length)
        return EnterHandlerDelegate.Result.Stop
    }

    private fun commentContinuation(previousText: String): String? {
        val match = COMMENT_LINE.matchEntire(previousText) ?: return null
        return match.groupValues[1] + match.groupValues[2] + " "
    }

    private fun listContinuation(previousText: String): String? {
        val match = LIST_LINE.matchEntire(previousText) ?: return null
        if (match.groupValues[3].isBlank()) return null
        val marker = match.groupValues[2]
        val nextMarker = marker.trim().removeSuffix(".").toIntOrNull()?.plus(1)?.let { "$it. " } ?: marker
        return match.groupValues[1] + nextMarker
    }

    private fun mathContinuation(documentText: CharSequence, previousStart: Int, previousText: String): String? {
        var delimiterCount = 0
        for (offset in 0 until previousStart + previousText.length) {
            if (documentText[offset] == '$' && (offset == 0 || documentText[offset - 1] != '\\')) delimiterCount++
        }
        if (delimiterCount % 2 == 0) return null
        val previousIndent = previousText.takeWhile(Char::isWhitespace)
        return previousIndent + "  "
    }

    private companion object {
        val COMMENT_LINE = Regex("^(\\s*)(///|//!|//)(?: ?)(.*)$")
        val LIST_LINE = Regex("^(\\s*)(- |\\+ |/ |[0-9]+\\. )(.*)$")
    }
}
