package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope

/** A function parameter, closure parameter, loop binding, or destructured `let` name. */
class TypstBindingDeclaration(node: ASTNode) : TypstPsiElement(node), PsiNameIdentifierOwner {

    override fun getNameIdentifier(): PsiElement? = node.findChildByType(TypstTokenTypes.IDENTIFIER)?.psi

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement {
        val identifier = nameIdentifier ?: return this
        identifier.replace(TypstElementFactory.createIdentifier(project, name))
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getUseScope(): SearchScope {
        var current = node.treeParent
        while (current != null) {
            if (
                current.elementType == TypstElementTypes.CLOSURE ||
                current.elementType == TypstElementTypes.LET_BINDING ||
                current.elementType == TypstElementTypes.FOR_LOOP
            ) {
                return LocalSearchScope(current.psi)
            }
            current = current.treeParent
        }
        return LocalSearchScope(containingFile)
    }
}
