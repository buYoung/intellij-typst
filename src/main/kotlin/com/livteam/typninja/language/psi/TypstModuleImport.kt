package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiReference
import com.livteam.typninja.language.references.TypstImportMemberReference
import com.livteam.typninja.language.references.TypstImportPathReference
import com.livteam.typninja.language.references.TypstModuleResolver

/**
 * PSI for a `#import` statement ([TypstElementTypes.MODULE_IMPORT]).
 *
 * Carries the navigable references of a relative import:
 *  - one [TypstImportPathReference] over the path string (`"utils.typ"`) → the imported file,
 *  - one [TypstImportMemberReference] over each explicit item name (`foo`, the `foo` of `foo as bar`)
 *    → the `#let foo` exported by the imported file.
 *
 * Each reference has a distinct in-element range, so `findReferenceAt` picks the one under the caret.
 * A package import (`@preview/…`) or a missing file yields references that resolve to `null`.
 */
class TypstModuleImport(node: ASTNode) : TypstPsiElement(node) {

    override fun getReferences(): Array<PsiReference> {
        val info = TypstModuleResolver.parseImport(node)
        val references = ArrayList<PsiReference>(1 + info.items.size)

        node.findChildByType(TypstElementTypes.STRING_LITERAL)?.let { pathNode ->
            info.pathString?.let { path ->
                references.add(TypstImportPathReference(this, rangeOf(pathNode), path))
            }
        }
        for (item in info.items) {
            references.add(TypstImportMemberReference(this, rangeOf(item.nameNode), info.pathString, item.moduleName))
        }
        return if (references.isEmpty()) PsiReference.EMPTY_ARRAY else references.toTypedArray()
    }

    override fun getReference(): PsiReference? = references.firstOrNull()

    private fun rangeOf(child: ASTNode): TextRange {
        val start = child.startOffset - node.startOffset
        return TextRange(start, start + child.textLength)
    }
}
