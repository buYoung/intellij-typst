package com.livteam.typninja.language.editor

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Consumer
import com.livteam.typninja.language.psi.TypstFile

/** Uses IntelliJ's own references so every supported Typst symbol highlights consistently. */
class TypstHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactory {

    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile): HighlightUsagesHandlerBase<PsiElement>? {
        val typstFile = file as? TypstFile ?: return null
        val offset = editor.caretModel.offset.coerceAtMost(file.textLength)
        val leaf = file.findElementAt(offset) ?: file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return null
        val targets = referenceTargets(file.findReferenceAt(offset) ?: file.findReferenceAt((offset - 1).coerceAtLeast(0)))
            .ifEmpty {
                val owner = PsiTreeUtil.getParentOfType(leaf, PsiNameIdentifierOwner::class.java, false)
                listOfNotNull(owner)
            }
        if (targets.isEmpty()) return null
        return TypstHighlightUsagesHandler(editor, typstFile, targets.distinct())
    }

    private fun referenceTargets(reference: com.intellij.psi.PsiReference?): List<PsiElement> = when (reference) {
        is PsiPolyVariantReference -> reference.multiResolve(false).mapNotNull { it.element }
        null -> emptyList()
        else -> listOfNotNull(reference.resolve())
    }
}

private class TypstHighlightUsagesHandler(
    editor: Editor,
    file: TypstFile,
    private val targets: List<PsiElement>,
) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {

    override fun getTargets(): List<PsiElement> = targets

    override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) {
        selectionConsumer.consume(targets)
    }

    override fun computeUsages(targets: List<PsiElement>) {
        val localScope = LocalSearchScope(myFile)
        for (target in targets) {
            ReferencesSearch.search(target, localScope).forEach { reference ->
                myReadUsages.add(reference.absoluteRange)
                true
            }
            val nameIdentifier = (target as? PsiNameIdentifierOwner)?.nameIdentifier
            myWriteUsages.add(nameIdentifier?.textRange ?: target.textRange)
        }
    }
}
