package com.livteam.typninja.language.analysis

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.livteam.typninja.language.TypstFileType
import com.livteam.typninja.language.psi.TypstFile

class TypstExportedSymbolIndex : ScalarIndexExtension<String>() {

    override fun getName(): ID<String, Void> = NAME

    override fun getIndexer(): DataIndexer<String, Void, FileContent> =
        DataIndexer { inputData ->
            exportedNames(inputData.contentAsText).associateWith { null }
        }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getVersion(): Int = 3

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { it.fileType == TypstFileType }

    override fun dependsOnFileContent(): Boolean = true

    companion object {
        val NAME: ID<String, Void> = ID.create("typst.exported.symbols")

        private val letPattern = Regex("""(?m)^\s*#?\s*let\s+([\p{L}_][\p{L}\p{N}_-]*)""")

        fun exportedNames(text: CharSequence): Set<String> =
            letPattern.findAll(text).map { it.groupValues[1] }.toSet()
    }
}

object TypstProjectSymbols {

    fun exportedSymbols(project: Project, scope: GlobalSearchScope = GlobalSearchScope.projectScope(project)): List<TypstIndexedExportedSymbol> {
        if (DumbService.isDumb(project)) return emptyList()
        val index = FileBasedIndex.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val symbols = ArrayList<TypstIndexedExportedSymbol>()
        for (name in index.getAllKeys(TypstExportedSymbolIndex.NAME, project)) {
            ProgressManager.checkCanceled()
            for (virtualFile in index.getContainingFiles(TypstExportedSymbolIndex.NAME, name, scope)) {
                ProgressManager.checkCanceled()
                val file = psiManager.findFile(virtualFile) as? TypstFile ?: continue
                val definition = TypstAnalysis.snapshot(file)?.exportedDefinition(name) ?: continue
                symbols.add(TypstIndexedExportedSymbol(name, file, definition))
            }
        }
        return symbols
    }

    fun exportedSymbolsWithName(
        project: Project,
        name: String,
        scope: GlobalSearchScope = GlobalSearchScope.projectScope(project),
    ): List<TypstIndexedExportedSymbol> {
        if (DumbService.isDumb(project)) return emptyList()
        val psiManager = PsiManager.getInstance(project)
        return FileBasedIndex.getInstance()
            .getContainingFiles(TypstExportedSymbolIndex.NAME, name, scope)
            .mapNotNull { virtualFile ->
                ProgressManager.checkCanceled()
                val file = psiManager.findFile(virtualFile) as? TypstFile ?: return@mapNotNull null
                val definition = TypstAnalysis.snapshot(file)?.exportedDefinition(name) ?: return@mapNotNull null
                TypstIndexedExportedSymbol(name, file, definition)
            }
    }
}
