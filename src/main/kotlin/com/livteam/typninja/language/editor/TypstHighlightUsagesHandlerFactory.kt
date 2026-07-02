package com.livteam.typninja.language.editor

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
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
        collectReferences(myFile) { reference ->
            if (reference.isReferenceTo(target)) {
                myReadUsages.add(reference.element.textRange)
            }
        }
        myReadUsages.add(target.textRange)
    }

    private fun collectReferences(element: PsiElement, consumer: (PsiReference) -> Unit) {
        ProgressManager.checkCanceled()
        for (reference in element.references) consumer(reference)
        for (child in element.children) collectReferences(child, consumer)
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
        collectRefs(myFile, name)
        myReadUsages.add(label.textRange)
    }

    private fun collectRefs(element: PsiElement, name: String) {
        ProgressManager.checkCanceled()
        if (element is TypstRef && element.referenceName == name && element.reference?.resolve() != null) {
            myReadUsages.add(element.textRange)
        }
        for (child in element.children) collectRefs(child, name)
    }
}
