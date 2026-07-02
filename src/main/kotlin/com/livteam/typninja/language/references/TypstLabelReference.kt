package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.livteam.typninja.language.psi.TypstRef

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
    PsiReferenceBase<TypstRef>(element, rangeInElement) {

    override fun resolve(): PsiElement? =
        TypstLabelResolver.resolveLabel(element.containingFile, element.referenceName)

    override fun getVariants(): Array<Any> = emptyArray()
}
