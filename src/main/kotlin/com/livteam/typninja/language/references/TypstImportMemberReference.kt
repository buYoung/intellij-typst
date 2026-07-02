package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.livteam.typninja.language.psi.TypstModuleImport

/**
 * Reference on an explicit import ITEM name (`foo` in `#import "utils.typ": foo`, or the `foo` of
 * `foo as bar`). Cmd+Click navigates to the `#let foo` exported by the imported file.
 *
 * The range covers the item's module-side identifier. An unresolved module / missing export resolves
 * to `null`.
 */
class TypstImportMemberReference(
    element: TypstModuleImport,
    rangeInElement: TextRange,
    private val path: String?,
    private val moduleName: String,
) : PsiReferenceBase<TypstModuleImport>(element, rangeInElement) {

    override fun resolve(): PsiElement? {
        val moduleFile = TypstModuleResolver.resolveModuleFile(element.containingFile, path) ?: return null
        return TypstModuleResolver.findExport(moduleFile, moduleName)
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
