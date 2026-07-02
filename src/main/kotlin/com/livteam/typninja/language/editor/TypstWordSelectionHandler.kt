package com.livteam.typninja.language.editor

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.psi.TypstTokenTypes

class TypstWordSelectionHandler : ExtendWordSelectionHandlerBase() {

    override fun canSelect(element: PsiElement): Boolean =
        isTypstSelectableLeaf(element) || element is TypstReferenceExpression

    override fun select(
        element: PsiElement,
        editorText: CharSequence,
        cursorOffset: Int,
        editor: Editor,
    ): List<TextRange> {
        val target = if (element is TypstReferenceExpression) element else element.parent
        if (target is TypstReferenceExpression) return listOf(target.textRange)
        return if (isTypstSelectableLeaf(element)) listOf(element.textRange) else emptyList()
    }

    override fun getMinimalTextRangeLength(element: PsiElement, text: CharSequence, cursorOffset: Int): Int {
        val target = if (element is TypstReferenceExpression) element else element.parent
        if (target is TypstReferenceExpression) return target.textLength
        return if (isTypstSelectableLeaf(element)) element.textLength else super.getMinimalTextRangeLength(element, text, cursorOffset)
    }

    private fun isTypstSelectableLeaf(element: PsiElement): Boolean =
        when (element.node.elementType) {
            TypstTokenTypes.IDENTIFIER,
            TypstTokenTypes.REF_MARKER,
            TypstTokenTypes.LABEL_DEF -> true
            else -> false
        }
}
