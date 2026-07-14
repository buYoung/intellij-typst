package com.livteam.typninja.language.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstFile

class TypstConvertMathToBlockIntention : TypstMathDisplayIntention(toBlock = true)

class TypstConvertMathToInlineIntention : TypstMathDisplayIntention(toBlock = false)

abstract class TypstMathDisplayIntention(private val toBlock: Boolean) : IntentionAction {
    override fun getText(): String = if (toBlock) "Convert to Typst block math" else "Convert to Typst inline math"
    override fun getFamilyName(): String = "Typst math"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is TypstFile) return false
        val math = mathAt(file, editor.caretModel.offset) ?: return false
        if (math.text.count { it == '\n' } > 0) return false
        return isBlock(math.text) != toBlock
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is TypstFile) return
        val math = mathAt(file, editor.caretModel.offset) ?: return
        val range = math.textRange
        if (range.length < 2) return
        WriteCommandAction.runWriteCommandAction(project, text, null, Runnable {
            if (toBlock) {
                editor.document.insertString(range.endOffset - 1, " ")
                editor.document.insertString(range.startOffset + 1, " ")
            } else {
                if (editor.document.charsSequence[range.endOffset - 2] == ' ') {
                    editor.document.deleteString(range.endOffset - 2, range.endOffset - 1)
                }
                if (editor.document.charsSequence[range.startOffset + 1] == ' ') {
                    editor.document.deleteString(range.startOffset + 1, range.startOffset + 2)
                }
            }
        }, file)
    }

    override fun startInWriteAction(): Boolean = false

    private fun mathAt(file: TypstFile, offset: Int): com.intellij.psi.PsiElement? {
        var element = file.findElementAt(offset.coerceAtMost((file.textLength - 1).coerceAtLeast(0)))
        while (element != null) {
            if (element.node?.elementType == TypstElementTypes.MATH) return element
            element = element.parent
        }
        return null
    }

    private fun isBlock(text: String): Boolean = text.length >= 4 && text[1].isWhitespace() && text[text.length - 2].isWhitespace()
}
