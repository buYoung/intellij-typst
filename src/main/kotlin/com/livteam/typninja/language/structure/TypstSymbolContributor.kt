package com.livteam.typninja.language.structure

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import com.livteam.typninja.language.analysis.TypstExportedSymbolIndex
import com.livteam.typninja.language.analysis.TypstProjectSymbols

class TypstSymbolContributor : ChooseByNameContributorEx {

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        if (DumbService.isDumb(project)) return
        for (name in com.intellij.util.indexing.FileBasedIndex.getInstance().getAllKeys(TypstExportedSymbolIndex.NAME, project)) {
            if (!processor.process(name)) return
        }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        if (DumbService.isDumb(parameters.project)) return
        val seen = HashSet<String>()
        for (symbol in TypstProjectSymbols.exportedSymbolsWithName(parameters.project, name, parameters.searchScope)) {
            val key = "${symbol.file.virtualFile?.path}:${symbol.definition.nameElement.textRange.startOffset}"
            if (!seen.add(key)) continue
            if (!processor.process(TypstNavigationItem(symbol.name, symbol.definition.declarationElement))) return
        }
    }
}

private class TypstNavigationItem(
    private val symbolName: String,
    private val target: PsiElement,
) : NavigationItem {

    override fun getName(): String = symbolName

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = symbolName
        override fun getLocationString(): String? = target.containingFile?.virtualFile?.path
        override fun getIcon(unused: Boolean) = target.getIcon(0)
    }

    override fun navigate(requestFocus: Boolean) {
        (target as? com.intellij.pom.Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (target as? com.intellij.pom.Navigatable)?.canNavigate() == true

    override fun canNavigateToSource(): Boolean = (target as? com.intellij.pom.Navigatable)?.canNavigateToSource() == true
}
