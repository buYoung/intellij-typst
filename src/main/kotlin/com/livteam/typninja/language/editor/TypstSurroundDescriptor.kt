package com.livteam.typninja.language.editor

import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.psi.TypstFile

/** IntelliJ의 표준 "Surround With" 메뉴에서 Typst 묶음을 적용한다. */
class TypstSurroundDescriptor : SurroundDescriptor {

    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> {
        if (file !is TypstFile || startOffset >= endOffset) return emptyArray()
        val first = file.findElementAt(startOffset) ?: return emptyArray()
        val last = file.findElementAt((endOffset - 1).coerceAtLeast(startOffset)) ?: return emptyArray()
        val commonParent = PsiTreeUtil.findCommonParent(first, last) ?: return emptyArray()
        return arrayOf(commonParent)
    }

    override fun getSurrounders(): Array<Surrounder> = SURROUNDERS

    override fun isExclusive(): Boolean = false

    private companion object {
        val SURROUNDERS: Array<Surrounder> = arrayOf(
            TypstTextSurrounder("Typst content block", "#[", "]"),
            TypstTextSurrounder("Typst code block", "#{", "}"),
            TypstTextSurrounder("Typst math", "$", "$"),
            TypstTextSurrounder("Typst strong emphasis", "*", "*"),
            TypstTextSurrounder("Typst emphasis", "_", "_"),
        )
    }
}

private class TypstTextSurrounder(
    private val description: String,
    private val prefix: String,
    private val suffix: String,
) : Surrounder {

    override fun getTemplateDescription(): String = description

    override fun isApplicable(elements: Array<out PsiElement>): Boolean = elements.isNotEmpty()

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        val selection = editor.selectionModel
        val startOffset = if (selection.hasSelection()) selection.selectionStart else elements.minOf { it.textRange.startOffset }
        val endOffset = if (selection.hasSelection()) selection.selectionEnd else elements.maxOf { it.textRange.endOffset }
        editor.document.insertString(endOffset, suffix)
        editor.document.insertString(startOffset, prefix)
        selection.removeSelection()
        val caretOffset = endOffset + prefix.length + suffix.length
        editor.caretModel.moveToOffset(caretOffset)
        return TextRange(startOffset + prefix.length, endOffset + prefix.length)
    }
}
