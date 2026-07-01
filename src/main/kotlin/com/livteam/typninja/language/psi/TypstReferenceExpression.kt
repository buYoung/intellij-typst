package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import com.livteam.typninja.language.references.TypstReference

/**
 * PSI for a code-context identifier USAGE ([TypstElementTypes.REFERENCE_EXPR]).
 *
 * The parser wraps every plain identifier read in code context (a bare `#data`, the callee `f` in
 * `#f(…)`, the base `obj` in `#obj.field`, operands of expressions, …) in this node — and ONLY those.
 * Definition names (`#let` names, parameters, `#for` bindings), field-access member names (`.field`),
 * named-argument keys and markup prose identifiers are deliberately NOT wrapped, so they carry no
 * reference and never offer a false "go to declaration".
 *
 * The node spans exactly its single [TypstTokenTypes.IDENTIFIER] leaf, so the reference range covers
 * the whole element and Cmd/Ctrl+Click anywhere on the name works.
 */
class TypstReferenceExpression(node: ASTNode) : TypstPsiElement(node) {

    /** The referenced name (the wrapped identifier's text). */
    val referenceName: String
        get() = node.findChildByType(TypstTokenTypes.IDENTIFIER)?.text ?: node.text

    override fun getReferences(): Array<PsiReference> = arrayOf(TypstReference(this))

    override fun getReference(): PsiReference = TypstReference(this)
}
