package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.PsiElementResolveResult
import com.livteam.typninja.language.psi.TypstRef
import com.livteam.typninja.language.psi.TypstLabelDefinition
import com.intellij.psi.util.PsiTreeUtil

/**
 * The [com.intellij.psi.PsiReference] carried by a `@reference` markup node ([TypstRef]). Powers
 * Go To Declaration / Cmd+Click from a `@name` reference to its `<name>` label definition in the
 * same file.
 *
 * The reference range covers the `@name` marker text (the whole `REF_MARKER` leaf), so a click
 * anywhere on the reference resolves. A `@name` with no matching `<name>` in the file resolves to
 * `null`, which the platform surfaces without navigating anywhere false.
 */
class TypstLabelReference(element: TypstRef, rangeInElement: TextRange) :
    PsiPolyVariantReferenceBase<TypstRef>(element, rangeInElement) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        TypstLabelResolver.resolveLabels(element.containingFile, element.referenceName)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> = emptyArray()

    override fun isReferenceTo(element: PsiElement): Boolean {
        return multiResolve(false).any { result ->
            val resolved = result.element ?: return@any false
            if (resolved.manager.areElementsEquivalent(resolved, element)) return@any true
            val owner = PsiTreeUtil.getParentOfType(resolved, TypstLabelDefinition::class.java, false)
            owner != null && owner.manager.areElementsEquivalent(owner, element)
        }
    }

    override fun handleElementRename(newElementName: String): PsiElement = element.renamed(newElementName)
}
