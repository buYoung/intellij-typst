package com.livteam.typninja.language.editor

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.livteam.typninja.language.TypstLanguage
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.psi.TypstTokenTypes

class TypstWordSelectionHandler : ExtendWordSelectionHandlerBase() {

    override fun canSelect(element: PsiElement): Boolean =
        element.language == TypstLanguage

    override fun select(
        element: PsiElement,
        editorText: CharSequence,
        cursorOffset: Int,
        editor: Editor,
    ): List<TextRange> {
        val ranges = linkedSetOf<TextRange>()
        innerRange(element)?.let(ranges::add)

        var current: PsiElement? = if (element is TypstReferenceExpression) element else element.parent
        while (current != null && current.containingFile != current) {
            if (isUsefulStructuralRange(current)) ranges.add(current.textRange)
            current = current.parent
        }
        return ranges.filter { it.length > 0 }.sortedBy { it.length }
    }

    override fun getMinimalTextRangeLength(element: PsiElement, text: CharSequence, cursorOffset: Int): Int {
        return innerRange(element)?.length
            ?: super.getMinimalTextRangeLength(element, text, cursorOffset)
    }

    private fun innerRange(element: PsiElement): TextRange? {
        val range = element.textRange
        return when (element.node.elementType) {
            TypstTokenTypes.IDENTIFIER,
            TypstTokenTypes.MATH_IDENT -> range
            TypstTokenTypes.REF_MARKER -> if (range.length > 1) TextRange(range.startOffset + 1, range.endOffset) else range
            TypstTokenTypes.LABEL_DEF -> if (range.length > 2) TextRange(range.startOffset + 1, range.endOffset - 1) else range
            TypstTokenTypes.STRING -> when {
                element.text.length > 1 && element.text.endsWith('"') -> TextRange(range.startOffset + 1, range.endOffset - 1)
                range.length > 1 -> TextRange(range.startOffset + 1, range.endOffset)
                else -> range
            }
            else -> null
        }

    }

    private fun isUsefulStructuralRange(element: PsiElement): Boolean = when (element.node.elementType) {
        TypstElementTypes.REFERENCE_EXPR,
        TypstElementTypes.MATH_REFERENCE,
        TypstElementTypes.FIELD_ACCESS,
        TypstElementTypes.FUNC_CALL,
        TypstElementTypes.ARGS,
        TypstElementTypes.UNARY,
        TypstElementTypes.BINARY,
        TypstElementTypes.ARRAY,
        TypstElementTypes.DICT,
        TypstElementTypes.PARENTHESIZED,
        TypstElementTypes.CONTENT_BLOCK,
        TypstElementTypes.CODE_BLOCK,
        TypstElementTypes.CODE_EXPRESSION,
        TypstElementTypes.MATH,
        TypstElementTypes.HEADING,
        TypstElementTypes.LIST_ITEM,
        TypstElementTypes.ENUM_ITEM,
        TypstElementTypes.TERM_ITEM,
        TypstElementTypes.LET_BINDING,
        TypstElementTypes.SET_RULE,
        TypstElementTypes.SHOW_RULE,
        TypstElementTypes.MODULE_IMPORT,
        TypstElementTypes.MODULE_INCLUDE -> true
        else -> element is TypstReferenceExpression
    }
}
