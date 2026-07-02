package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.livteam.typninja.language.psi.TypstModuleImport

/**
 * Reference on the PATH string of a relative `#import`/`#include` (`#import "utils.typ": …`).
 * Cmd+Click on the path navigates to the imported [com.livteam.typninja.language.psi.TypstFile].
 *
 * The range covers the string literal (quotes included). Package specs (`@preview/…`) and missing
 * files resolve to `null` — no false navigation.
 */
class TypstImportPathReference(element: TypstModuleImport, rangeInElement: TextRange, private val path: String) :
    PsiReferenceBase<TypstModuleImport>(element, rangeInElement) {

    override fun resolve(): PsiElement? =
        TypstModuleResolver.resolveModuleFile(element.containingFile, path)

    override fun getVariants(): Array<Any> = emptyArray()
}
