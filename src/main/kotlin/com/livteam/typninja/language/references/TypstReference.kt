package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.analysis.TypstDefinitionKind
import com.livteam.typninja.language.psi.TypstElementFactory

/**
 * The [com.intellij.psi.PsiReference] carried by a code-context identifier usage
 * ([TypstReferenceExpression]). It powers Go To Declaration / Cmd+Click.
 *
 * The range is the whole element (the wrapped identifier), so a click anywhere on the name resolves.
 * Resolution delegates to the shared Typst analysis layer. File-local declarations, supported
 * relative imports, and standard-library builtins resolve conservatively; unknown names resolve to
 * `null`, which the platform surfaces as "cannot find declaration" without navigating anywhere false.
 *
 * [getVariants] returns nothing: completion is out of scope for reference resolution (F1).
 */
class TypstReference(element: TypstReferenceExpression) :
    PsiReferenceBase<TypstReferenceExpression>(element, TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? = TypstReferenceResolver.resolve(element)

    override fun isReferenceTo(element: PsiElement): Boolean {
        val definition = TypstReferenceResolver.resolveResult(this.element)?.definition ?: return false
        val localOwner = PsiTreeUtil.getParentOfType(definition.nameElement, PsiNameIdentifierOwner::class.java, false)
        if (equivalent(definition.nameElement, element) || (localOwner != null && equivalent(localOwner, element))) return true
        if (definition.kind == TypstDefinitionKind.IMPORTED_SYMBOL && definition.name != definition.sourceName) return false
        val resolved = definition.navigationElement
        if (resolved.manager.areElementsEquivalent(resolved, element)) return true
        if (element is PsiNameIdentifierOwner) {
            val nameIdentifier = element.nameIdentifier
            if (nameIdentifier != null && resolved.manager.areElementsEquivalent(resolved, nameIdentifier)) return true
        }
        val resolvedOwner = PsiTreeUtil.getParentOfType(resolved, PsiNameIdentifierOwner::class.java, false)
        return resolvedOwner != null && resolved.manager.areElementsEquivalent(resolvedOwner, element)
    }

    override fun handleElementRename(newElementName: String): PsiElement =
        element.replace(TypstElementFactory.createReferenceExpression(element.project, newElementName))

    override fun getVariants(): Array<Any> = emptyArray()

    private fun equivalent(left: PsiElement, right: PsiElement): Boolean =
        left.manager.areElementsEquivalent(left, right)
}
