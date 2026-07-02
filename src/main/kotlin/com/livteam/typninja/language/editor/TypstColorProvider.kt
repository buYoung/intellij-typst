package com.livteam.typninja.language.editor

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.psi.TypstReferenceExpression
import java.awt.Color

class TypstColorProvider : ElementColorProvider {

    override fun getColorFrom(element: PsiElement): Color? {
        val text = element.text.trim()
        parseHexColor(text)?.let { return it }
        val reference = PsiTreeUtil.getParentOfType(element, TypstReferenceExpression::class.java, false)
        if (reference?.referenceName != "rgb") return null
        val call = reference.parent
        val argsText = call?.text ?: return null
        return parseRgbCall(argsText)
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        // Color editing is intentionally omitted until PSI replacement for all supported syntaxes is safe.
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
            .map { it.trim().removeSuffix("%") }
        if (channels.size < 3) return null
        val values = channels.take(3).mapNotNull { it.toIntOrNull() }
        if (values.size != 3 || values.any { it !in 0..255 }) return null
        return Color(values[0], values[1], values[2])
    }
}
