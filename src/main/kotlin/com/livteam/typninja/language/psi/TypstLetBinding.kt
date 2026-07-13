package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner

/**
 * PSI for a `#let` binding ([TypstElementTypes.LET_BINDING]).
 *
 * A let binding is the file-local declaration site for a variable (`#let data = …`) or a function
 * (`#let f(x) = …`). Making it a [PsiNameIdentifierOwner] turns the bound name into a resolvable,
 * navigable target: [TypstReferenceExpression] usages resolve here (see [TypstReferenceResolver]),
 * and the platform's Go To Declaration / (future) Find Usages & Rename infrastructure recognises the
 * name element.
 *
 * The name is the FIRST direct identifier child after `let` (the parser emits the name as a bare
 * [TypstTokenTypes.IDENTIFIER] leaf; parameter identifiers live nested inside a
 * [TypstElementTypes.PARAMS] child, so they are not confused with the name). Destructuring / `_`
 * bindings own no single name and report `null`.
 *
 * Rename is deferred to a later phase: [setName] throws [IncorrectOperationException].
 */
class TypstLetBinding(node: ASTNode) : TypstPsiElement(node), PsiNameIdentifierOwner {

    override fun getNameIdentifier(): PsiElement? {
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType == TypstTokenTypes.IDENTIFIER) return child.psi
            child = child.treeNext
        }
        return null
    }

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement {
        val identifier = nameIdentifier ?: return this
        identifier.replace(TypstElementFactory.createIdentifier(project, name))
        return this
    }

    /** Navigation lands on the bound name (not the leading `let` keyword) when a name exists. */
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
}
