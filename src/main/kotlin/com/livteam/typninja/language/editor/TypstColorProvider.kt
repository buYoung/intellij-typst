package com.livteam.typninja.language.editor

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.livteam.typninja.language.psi.TypstElementFactory
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstFieldAccess
import com.livteam.typninja.language.psi.TypstReferenceExpression
import java.awt.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class TypstColorProvider : ElementColorProvider {

    override fun getColorFrom(element: PsiElement): Color? {
        // IntelliJ renders color swatches as line markers, which must be anchored to a leaf PSI
        // element. The nearest call is still discovered from that leaf below.
        if (element.firstChild != null) return null
        parseHexColor(element.text.trim())?.let { return it }
        val call = colorCall(element) ?: return null
        return parseColorCall(call.functionName, call.element.text)
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        if (parseHexColor(element.text.trim()) != null) {
            val document = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile) ?: return
            val replacement = formatHexColor(element.text.trim(), color)
            WriteCommandAction.runWriteCommandAction(element.project) {
                document.replaceString(element.textRange.startOffset, element.textRange.endOffset, replacement)
                PsiDocumentManager.getInstance(element.project).commitDocument(document)
            }
            return
        }
        val call = colorCall(element) ?: return
        if (parseColorCall(call.functionName, call.element.text) == null) return
        WriteCommandAction.runWriteCommandAction(element.project) {
            call.element.replace(TypstElementFactory.createRgbCall(element.project, color.red, color.green, color.blue))
        }
    }

    private fun parseHexColor(text: String): Color? {
        val literal = text.removeSurrounding("\"")
        if (!literal.startsWith("#")) return null
        val hex = literal.removePrefix("#")
        if (hex.length !in setOf(3, 4, 6, 8) || !hex.all(::isHexDigit)) return null
        val expanded = if (hex.length <= 4) hex.map { "$it$it" }.joinToString("") else hex
        val red = expanded.substring(0, 2).toInt(16)
        val green = expanded.substring(2, 4).toInt(16)
        val blue = expanded.substring(4, 6).toInt(16)
        val alpha = expanded.drop(6).takeIf { it.length == 2 }?.toInt(16) ?: 255
        return Color(red, green, blue, alpha)
    }

    private fun formatHexColor(originalText: String, color: Color): String {
        val literal = originalText.removeSurrounding("\"")
        val hasAlpha = literal.removePrefix("#").length in setOf(4, 8)
        val value = if (hasAlpha) {
            "#%02X%02X%02X%02X".format(color.red, color.green, color.blue, color.alpha)
        } else {
            "#%02X%02X%02X".format(color.red, color.green, color.blue)
        }
        return if (originalText.startsWith('"')) "\"$value\"" else value
    }

    private fun colorCall(element: PsiElement): ColorCall? {
        val call = generateSequence(element) { it.parent }
            .firstOrNull { it.node?.elementType == TypstElementTypes.FUNC_CALL }
            ?: return null
        val callee = firstMeaningfulChild(call) ?: return null
        val functionName = when (callee) {
            is TypstReferenceExpression -> callee.referenceName
            is TypstFieldAccess -> callee.memberName
            else -> null
        } ?: return null
        return ColorCall(call, functionName)
    }

    private fun parseColorCall(functionName: String, text: String): Color? {
        val channels = callArguments(text) ?: return null
        return when (functionName) {
            "rgb" -> parseRgb(channels)
            "luma" -> parseLuma(channels)
            "cmyk" -> parseCmyk(channels)
            "hsv" -> parseHsv(channels)
            "hsl" -> parseHsl(channels)
            "oklab" -> parseOklab(channels)
            "oklch" -> parseOklch(channels)
            else -> null
        }
    }

    private fun parseRgb(channels: List<String>): Color? {
        if (channels.size == 1) return parseHexColor(channels.single())
        if (channels.size !in 3..4) return null
        val values = channels.take(3).map(::parseRgbChannel)
        val alpha = channels.getOrNull(3)?.let(::parseAlpha) ?: 255
        return values.takeIf { it.all { value -> value != null } }
            ?.let { Color(it[0]!!, it[1]!!, it[2]!!, alpha) }
    }

    private fun parseLuma(channels: List<String>): Color? {
        if (channels.size !in 1..2) return null
        val value = parseRgbChannel(channels[0]) ?: return null
        val alpha = channels.getOrNull(1)?.let(::parseAlpha) ?: 255
        return Color(value, value, value, alpha)
    }

    private fun parseCmyk(channels: List<String>): Color? {
        if (channels.size !in 4..5) return null
        val values = channels.take(4).map(::parseUnitChannel)
        if (values.any { it == null }) return null
        val cyan = values[0]!!
        val magenta = values[1]!!
        val yellow = values[2]!!
        val black = values[3]!!
        val alpha = channels.getOrNull(4)?.let(::parseAlpha) ?: 255
        return Color(
            (255 * (1 - cyan) * (1 - black)).toInt().coerceIn(0, 255),
            (255 * (1 - magenta) * (1 - black)).toInt().coerceIn(0, 255),
            (255 * (1 - yellow) * (1 - black)).toInt().coerceIn(0, 255),
            alpha,
        )
    }

    private fun parseHsv(channels: List<String>): Color? {
        if (channels.size !in 3..4) return null
        val hue = parseHue(channels[0]) ?: return null
        val saturation = parseUnitChannel(channels[1]) ?: return null
        val value = parseUnitChannel(channels[2]) ?: return null
        val alpha = channels.getOrNull(3)?.let(::parseAlpha) ?: 255
        return Color(Color.HSBtoRGB((hue / 360.0).toFloat(), saturation.toFloat(), value.toFloat()), true)
            .let { Color(it.red, it.green, it.blue, alpha) }
    }

    private fun parseHsl(channels: List<String>): Color? {
        if (channels.size !in 3..4) return null
        val hue = parseHue(channels[0]) ?: return null
        val saturation = parseUnitChannel(channels[1]) ?: return null
        val lightness = parseUnitChannel(channels[2]) ?: return null
        val chroma = (1 - abs(2 * lightness - 1)) * saturation
        val intermediate = hue / 60.0
        val second = chroma * (1 - abs(intermediate % 2 - 1))
        val (red, green, blue) = when {
            intermediate < 1 -> Triple(chroma, second, 0.0)
            intermediate < 2 -> Triple(second, chroma, 0.0)
            intermediate < 3 -> Triple(0.0, chroma, second)
            intermediate < 4 -> Triple(0.0, second, chroma)
            intermediate < 5 -> Triple(second, 0.0, chroma)
            else -> Triple(chroma, 0.0, second)
        }
        val match = lightness - chroma / 2
        val alpha = channels.getOrNull(3)?.let(::parseAlpha) ?: 255
        return Color(toRgb(red + match), toRgb(green + match), toRgb(blue + match), alpha)
    }

    private fun parseOklab(channels: List<String>): Color? {
        if (channels.size !in 3..4) return null
        val lightness = channels[0].toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: return null
        val a = channels[1].toDoubleOrNull() ?: return null
        val b = channels[2].toDoubleOrNull() ?: return null
        val alpha = channels.getOrNull(3)?.let(::parseAlpha) ?: 255
        return oklabToColor(lightness, a, b, alpha)
    }

    private fun parseOklch(channels: List<String>): Color? {
        if (channels.size !in 3..4) return null
        val lightness = channels[0].toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: return null
        val chroma = channels[1].toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
        val hue = parseHue(channels[2]) ?: return null
        val radians = Math.toRadians(hue)
        val alpha = channels.getOrNull(3)?.let(::parseAlpha) ?: 255
        return oklabToColor(lightness, chroma * kotlin.math.cos(radians), chroma * kotlin.math.sin(radians), alpha)
    }

    private fun oklabToColor(lightness: Double, a: Double, b: Double, alpha: Int): Color {
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val blue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return Color(toRgb(linearToSrgb(red)), toRgb(linearToSrgb(green)), toRgb(linearToSrgb(blue)), alpha)
    }

    private fun callArguments(text: String): List<String>? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        return text.substring(open + 1, close).split(',').map(String::trim)
    }

    private fun parseRgbChannel(value: String): Int? {
        if (value.endsWith('%')) return value.removeSuffix("%").toDoubleOrNull()
            ?.takeIf { it in 0.0..100.0 }?.let { (it * 255 / 100).toInt() }
        val parsed = value.toDoubleOrNull() ?: return null
        return when {
            parsed in 0.0..1.0 && value.contains('.') -> (parsed * 255).toInt()
            parsed in 0.0..255.0 && parsed % 1 == 0.0 -> parsed.toInt()
            else -> null
        }
    }

    private fun parseUnitChannel(value: String): Double? {
        if (value.endsWith('%')) return value.removeSuffix("%").toDoubleOrNull()?.div(100)?.takeIf { it in 0.0..1.0 }
        return value.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
    }

    private fun parseAlpha(value: String): Int = parseRgbChannel(value) ?: parseUnitChannel(value)?.let { (it * 255).toInt() } ?: 255

    private fun parseHue(value: String): Double? = value.removeSuffix("deg").toDoubleOrNull()
        ?.let { ((it % 360) + 360) % 360 }

    private fun toRgb(value: Double): Int = (min(1.0, max(0.0, value)) * 255).toInt().coerceIn(0, 255)

    private fun linearToSrgb(value: Double): Double = if (value <= 0.0031308) 12.92 * value else 1.055 * value.pow(1 / 2.4) - 0.055

    private fun isHexDigit(character: Char): Boolean =
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'

    private fun firstMeaningfulChild(element: PsiElement): PsiElement? =
        element.children.firstOrNull { child -> !child.text.all(Char::isWhitespace) }

    private data class ColorCall(val element: PsiElement, val functionName: String)
}
