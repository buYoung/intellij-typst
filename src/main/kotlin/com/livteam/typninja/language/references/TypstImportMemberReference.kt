package com.livteam.typninja.language.references

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.psi.TypstElementFactory
import com.livteam.typninja.language.psi.TypstImportItem

/**
 * Reference on an explicit import ITEM name (`foo` in `#import "utils.typ": foo`, or the `foo` of
 * `foo as bar`). Cmd+Click navigates to the `#let foo` exported by the imported file.
 *
 * The range covers the item's module-side identifier. An unresolved module / missing export resolves
 * to `null`.
 */
class TypstImportMemberReference(
    element: TypstImportItem,
    rangeInElement: TextRange,
    private val path: String?,
    private val sourceSegments: List<String>,
    private val sourceSegmentIndex: Int,
) : PsiReferenceBase<TypstImportItem>(element, rangeInElement) {

    override fun resolve(): PsiElement? {
        val moduleFile = TypstModuleResolver.resolveModuleFile(element.containingFile, path) ?: return null
        return TypstModuleResolver.findImportMember(moduleFile, sourceSegments, sourceSegmentIndex)
    }

    override fun isReferenceTo(candidate: PsiElement): Boolean {
        val resolved = resolve() ?: return false
        if (resolved.manager.areElementsEquivalent(resolved, candidate)) return true
        val owner = PsiTreeUtil.getParentOfType(resolved, PsiNameIdentifierOwner::class.java, false)
        if (owner != null && resolved.manager.areElementsEquivalent(owner, candidate)) return true
        val candidateIdentifier = (candidate as? PsiNameIdentifierOwner)?.nameIdentifier
        return candidateIdentifier != null && resolved.manager.areElementsEquivalent(resolved, candidateIdentifier)
    }

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        TypstElementFactory.requireIdentifier(newElementName)
        val renamedSegments = sourceSegments.toMutableList()
        if (sourceSegmentIndex !in renamedSegments.indices) return element
        renamedSegments[sourceSegmentIndex] = newElementName
        val localName = element.localAlias
            ?: if (sourceSegmentIndex == renamedSegments.lastIndex) newElementName else element.name.orEmpty()
        val replacement = TypstElementFactory.createImportItem(
            element.project,
            renamedSegments.joinToString("."),
            localName,
        )
        return element.replace(replacement)
    }
}
