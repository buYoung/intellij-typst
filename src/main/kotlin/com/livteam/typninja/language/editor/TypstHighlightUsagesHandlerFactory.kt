package com.livteam.typninja.language.editor

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstRef
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.references.TypstLabelResolver

class TypstHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactory {

    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile): HighlightUsagesHandlerBase<PsiElement>? {
        if (file !is TypstFile) return null
        val element = file.findElementAt(editor.caretModel.offset) ?: return null
        val referenceExpression = PsiTreeUtil.getParentOfType(element, TypstReferenceExpression::class.java, false)
        if (referenceExpression != null && referenceExpression.reference?.resolve() != null) {
            return TypstSymbolHighlightUsagesHandler(editor, file, referenceExpression)
        }
        val ref = PsiTreeUtil.getParentOfType(element, TypstRef::class.java, false)
        if (ref != null && ref.reference?.resolve() != null) {
            return TypstLabelHighlightUsagesHandler(editor, file, ref)
        }
        return null
    }
}

private class TypstSymbolHighlightUsagesHandler(
    editor: Editor,
    file: TypstFile,
    private val source: TypstReferenceExpression,
) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {

    override fun getTargets(): List<PsiElement> =
        source.reference?.resolve()?.let { listOf(it) }.orEmpty()

    override fun selectTargets(targets: List<PsiElement>, selectionConsumer: com.intellij.util.Consumer<in List<PsiElement>>) {
        selectionConsumer.consume(targets)
    }

    override fun computeUsages(targets: List<PsiElement>) {
        val target = targets.firstOrNull() ?: return
        val candidates = com.livteam.typninja.language.analysis.TypstAnalysis.snapshot(myFile)
            ?.referenceCandidates
            ?.get(source.referenceName)
            .orEmpty()
        candidates.forEach { candidate ->
            candidate.references.filter { it.isReferenceTo(target) }.forEach { reference -> myReadUsages.add(reference.element.textRange) }
        }
        myReadUsages.add(target.textRange)
    }
}

private class TypstLabelHighlightUsagesHandler(
    editor: Editor,
    file: TypstFile,
    private val source: TypstRef,
) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {

    override fun getTargets(): List<PsiElement> =
        source.reference?.resolve()?.let { listOf(it) }.orEmpty()

    override fun selectTargets(targets: List<PsiElement>, selectionConsumer: com.intellij.util.Consumer<in List<PsiElement>>) {
        selectionConsumer.consume(targets)
    }

    override fun computeUsages(targets: List<PsiElement>) {
        val label = targets.firstOrNull() ?: return
        val name = TypstLabelResolver.labelName(label.text)
        com.livteam.typninja.language.analysis.TypstAnalysis.snapshot(myFile)
            ?.referenceCandidates
            ?.get(name)
            .orEmpty()
            .filterIsInstance<TypstRef>()
            .filter { it.reference?.resolve() != null }
            .forEach { myReadUsages.add(it.textRange) }
        myReadUsages.add(label.textRange)
    }
}
