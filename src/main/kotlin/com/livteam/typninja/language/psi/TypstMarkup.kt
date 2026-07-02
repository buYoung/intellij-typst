package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.paths.WebReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiReference

class TypstMarkup(node: ASTNode) : TypstPsiElement(node) {

    override fun getReferences(): Array<PsiReference> {
        val references = ArrayList<PsiReference>()
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType == TypstTokenTypes.LINK) {
                val linkText = child.text
                if (linkText.startsWith("http://") || linkText.startsWith("https://")) {
                    val start = child.startOffset - node.startOffset
                    references.add(WebReference(this, TextRange(start, start + child.textLength), linkText))
                }
            }
            child = child.treeNext
        }
        return if (references.isEmpty()) PsiReference.EMPTY_ARRAY else references.toTypedArray()
    }
}
