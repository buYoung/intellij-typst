package com.livteam.typninja.language.references

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.livteam.typninja.language.lexer.TypstLexer
import com.livteam.typninja.language.psi.TypstLetBinding
import com.livteam.typninja.language.psi.TypstImportItem
import com.livteam.typninja.language.psi.TypstLabelDefinition
import com.livteam.typninja.language.psi.TypstModuleImport
import com.livteam.typninja.language.psi.TypstTokenTypes

class TypstFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(
            TypstLexer(),
            TokenSet.create(TypstTokenTypes.IDENTIFIER, TypstTokenTypes.REF_MARKER, TypstTokenTypes.LABEL_DEF),
            TokenSet.create(TypstTokenTypes.LINE_COMMENT, TypstTokenTypes.BLOCK_COMMENT),
            TokenSet.create(TypstTokenTypes.STRING, TypstTokenTypes.RAW_TEXT),
            TokenSet.create(TokenType.BAD_CHARACTER),
        )

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean =
        ownerOf(psiElement) is TypstLetBinding ||
            ownerOf(psiElement) is TypstImportItem ||
            ownerOf(psiElement) is TypstModuleImport ||
            ownerOf(psiElement) is TypstLabelDefinition

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String =
        when (val owner = ownerOf(element)) {
            is TypstLetBinding -> if (owner.node.findChildByType(com.livteam.typninja.language.psi.TypstElementTypes.PARAMS) != null) {
                "Typst function"
            } else {
                "Typst variable"
            }
            is TypstImportItem -> "Typst imported name"
            is TypstModuleImport -> "Typst module alias"
            is TypstLabelDefinition -> "Typst label"
            else -> "Typst symbol"
        }

    override fun getDescriptiveName(element: PsiElement): String =
        ownerOf(element)?.name ?: element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        getDescriptiveName(element)

    private fun ownerOf(element: PsiElement): PsiNameIdentifierOwner? =
        element as? PsiNameIdentifierOwner ?: element.parent as? PsiNameIdentifierOwner
}
