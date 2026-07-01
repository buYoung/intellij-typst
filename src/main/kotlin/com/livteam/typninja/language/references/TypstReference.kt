package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.livteam.typninja.language.psi.TypstReferenceExpression

/**
 * The [com.intellij.psi.PsiReference] carried by a code-context identifier usage
 * ([TypstReferenceExpression]). It powers Go To Declaration / Cmd+Click.
 *
 * The range is the whole element (the wrapped identifier), so a click anywhere on the name resolves.
 * Resolution is FILE-LOCAL only ([TypstReferenceResolver]); an identifier with no file-local
 * definition (e.g. a built-in, or a symbol brought in by a cross-file `#import`) resolves to `null`,
 * which the platform surfaces as "cannot find declaration" without navigating anywhere false.
 *
 * [getVariants] returns nothing: completion is out of scope for reference resolution (F1).
 */
class TypstReference(element: TypstReferenceExpression) :
    PsiReferenceBase<TypstReferenceExpression>(element, TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? = TypstReferenceResolver.resolve(element)

    override fun getVariants(): Array<Any> = emptyArray()
}
