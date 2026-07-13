package com.livteam.typninja.language.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import com.livteam.typninja.language.TypstLanguage

object TypstElementFactory {

    private val reservedWords = setOf(
        "and", "as", "break", "context", "continue", "else", "for", "if", "import", "include", "in", "let", "not", "or", "return", "set", "show", "while",
    )

    fun requireIdentifier(name: String) {
        if (!isIdentifier(name)) {
            throw IncorrectOperationException("Invalid Typst identifier: $name")
        }
    }

    fun createIdentifier(project: Project, name: String): PsiElement {
        requireIdentifier(name)
        val file = createFile(project, "#let $name = none")
        return PsiTreeUtil.findChildOfType(file, TypstLetBinding::class.java)?.nameIdentifier
            ?: throw IncorrectOperationException("Cannot create Typst identifier: $name")
    }

    fun createReferenceExpression(project: Project, name: String): TypstReferenceExpression {
        requireIdentifier(name)
        val file = createFile(project, "#$name")
        return PsiTreeUtil.findChildOfType(file, TypstReferenceExpression::class.java)
            ?: throw IncorrectOperationException("Cannot create Typst reference: $name")
    }

    fun createImportItem(project: Project, sourceName: String, localName: String): TypstImportItem {
        val sourceSegments = sourceName.split('.')
        if (sourceSegments.isEmpty() || sourceSegments.any { !isIdentifier(it) }) {
            throw IncorrectOperationException("Invalid Typst import path: $sourceName")
        }
        requireIdentifier(localName)
        val alias = if (sourceName == localName) "" else " as $localName"
        val file = createFile(project, "#import \"base.typ\": $sourceName$alias")
        return PsiTreeUtil.findChildOfType(file, TypstImportItem::class.java)
            ?: throw IncorrectOperationException("Cannot create Typst import item")
    }

    fun createLabel(project: Project, name: String): TypstLabelDefinition {
        requireIdentifier(name)
        val file = createFile(project, "<$name>")
        return PsiTreeUtil.findChildOfType(file, TypstLabelDefinition::class.java)
            ?: throw IncorrectOperationException("Cannot create Typst label: $name")
    }

    fun createLabelReference(project: Project, name: String): TypstRef {
        requireIdentifier(name)
        val file = createFile(project, "@$name")
        return PsiTreeUtil.findChildOfType(file, TypstRef::class.java)
            ?: throw IncorrectOperationException("Cannot create Typst label reference: $name")
    }

    fun createRgbCall(project: Project, red: Int, green: Int, blue: Int): PsiElement {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
        val file = createFile(project, "#rgb($red, $green, $blue)")
        val reference = PsiTreeUtil.findChildOfType(file, TypstReferenceExpression::class.java)
            ?: throw IncorrectOperationException("Cannot create Typst rgb call")
        return reference.parent.takeIf { it.node.elementType == TypstElementTypes.FUNC_CALL }
            ?: throw IncorrectOperationException("Cannot create Typst rgb call")
    }

    private fun isIdentifier(name: String): Boolean {
        if (name.isEmpty() || name in reservedWords) return false
        if (!Character.isLetter(name.codePointAt(0)) && name[0] != '_') return false
        var offset = Character.charCount(name.codePointAt(0))
        while (offset < name.length) {
            val codePoint = name.codePointAt(offset)
            if (!Character.isLetterOrDigit(codePoint) && codePoint != '_'.code && codePoint != '-'.code) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun createFile(project: Project, text: String) =
        PsiFileFactory.getInstance(project).createFileFromText("element.typ", TypstLanguage, text)
}
