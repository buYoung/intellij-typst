package com.livteam.typninja.language.structure

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.psi.TypstFile

class TypstStructureViewFactory : PsiStructureViewFactory {

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        val file = psiFile as? TypstFile ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                StructureViewModelBase(file, editor, TypstStructureElement(file))
        }
    }
}

private class TypstStructureElement(
    private val element: PsiElement,
    private val label: String? = null,
) : StructureViewTreeElement {

    override fun getValue(): Any = element

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = label ?: presentableText(element)
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean) = element.getIcon(0)
    }

    override fun getChildren(): Array<TreeElement> {
        val file = element as? TypstFile ?: return TreeElement.EMPTY_ARRAY
        val snapshot = TypstAnalysis.snapshot(file) ?: return TreeElement.EMPTY_ARRAY
        val children = ArrayList<TreeElement>()
        for (definition in snapshot.declarations) {
            if (snapshot.exportedDefinition(definition.name) == definition) {
                children.add(TypstStructureElement(definition.declarationElement, definition.name))
            }
        }
        for (labelDefinition in snapshot.labels) {
            children.add(TypstStructureElement(labelDefinition.nameElement, "<${labelDefinition.name}>"))
        }
        for (heading in snapshot.headings) {
            children.add(TypstStructureElement(heading, heading.text.trim().lineSequence().firstOrNull().orEmpty()))
        }
        for (locatedImport in snapshot.imports) {
            locatedImport.summary.pathString?.let {
                children.add(TypstStructureElement(locatedImport.node.psi, "import $it"))
            }
        }
        return children.toTypedArray()
    }

    override fun navigate(requestFocus: Boolean) {
        (element as? NavigatablePsiElement)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? NavigatablePsiElement)?.canNavigate() == true

    override fun canNavigateToSource(): Boolean = (element as? NavigatablePsiElement)?.canNavigateToSource() == true

    private fun presentableText(element: PsiElement): @NlsSafe String =
        element.text.trim().lineSequence().firstOrNull().orEmpty().ifEmpty { element.toString() }
}
