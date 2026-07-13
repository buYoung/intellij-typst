package com.livteam.typninja.language.editor

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.psi.TypstElementFactory
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstReferenceExpression
import java.awt.Color

class TypstColorProvider : ElementColorProvider {

    override fun getColorFrom(element: PsiElement): Color? {
        val text = element.text.trim()
        parseHexColor(text)?.let { return it }
        val reference = PsiTreeUtil.getParentOfType(element, TypstReferenceExpression::class.java, false)
        if (reference?.referenceName != "rgb") return null
        val argsText = rgbCall(reference)?.text ?: return null
        return parseRgbCall(argsText)
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        if (parseHexColor(element.text.trim()) != null) {
            val document = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile) ?: return
            val replacement = "#%02X%02X%02X".format(color.red, color.green, color.blue)
            WriteCommandAction.runWriteCommandAction(element.project) {
                document.replaceString(element.textRange.startOffset, element.textRange.endOffset, replacement)
                PsiDocumentManager.getInstance(element.project).commitDocument(document)
            }
            return
        }
        val call = rgbCall(element) ?: return
        if (parseRgbCall(call.text) == null) return
        WriteCommandAction.runWriteCommandAction(element.project) {
            call.replace(TypstElementFactory.createRgbCall(element.project, color.red, color.green, color.blue))
        }
    }

    private fun parseHexColor(text: String): Color? {
        if (!text.startsWith("#")) return null
        val hex = text.removePrefix("#")
        if (hex.length != 6 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return Color(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
    }

    private fun parseRgbCall(text: String): Color? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val channels = text.substring(open + 1, close)
            .split(',')
            .map { it.trim() }
        if (channels.size != 3 || channels.any { it.endsWith('%') }) return null
        val values = channels.mapNotNull { it.toIntOrNull() }
        if (values.size != 3 || values.any { it !in 0..255 }) return null
        return Color(values[0], values[1], values[2])
    }

    private fun rgbCall(element: PsiElement): PsiElement? {
        val reference = PsiTreeUtil.getParentOfType(element, TypstReferenceExpression::class.java, false) ?: return null
        val call = reference.parent
        return call?.takeIf { it.node.elementType == TypstElementTypes.FUNC_CALL }
    }
}
