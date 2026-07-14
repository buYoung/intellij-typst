package com.livteam.typninja.language.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

class TypstBibliographyIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME
    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { input ->
        bibliographyKeys(input.file.extension, input.contentAsText).associateWith { null }
    }
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = 1
    override fun getInputFilter(): FileBasedIndex.InputFilter = FileBasedIndex.InputFilter {
        it.extension?.lowercase() in EXTENSIONS
    }
    override fun dependsOnFileContent(): Boolean = true

    companion object {
        val NAME: ID<String, Void> = ID.create("typst.bibliography.keys")
        private val EXTENSIONS = setOf("bib", "yaml", "yml")
        private val BIB_KEY = Regex("(?m)^\\s*@[A-Za-z]+\\s*\\{\\s*([^,\\s]+)")
        private val YAML_KEY = Regex("(?m)^([A-Za-z0-9_.:+/-]+):\\s*(?:$|\\{)")

        private fun bibliographyKeys(extension: String?, text: CharSequence): Set<String> = when (extension?.lowercase()) {
            "bib" -> BIB_KEY.findAll(text).map { it.groupValues[1] }.toSet()
            "yaml", "yml" -> YAML_KEY.findAll(text).map { it.groupValues[1] }.toSet()
            else -> emptySet()
        }
    }
}

object TypstProjectBibliography {
    fun keys(project: Project): Collection<String> =
        if (DumbService.isDumb(project)) emptyList()
        else FileBasedIndex.getInstance().getAllKeys(TypstBibliographyIndex.NAME, project)

    fun definitions(project: Project, key: String, scope: GlobalSearchScope = GlobalSearchScope.projectScope(project)): List<PsiElement> {
        if (DumbService.isDumb(project)) return emptyList()
        val psiManager = PsiManager.getInstance(project)
        return FileBasedIndex.getInstance().getContainingFiles(TypstBibliographyIndex.NAME, key, scope).mapNotNull { virtualFile ->
            ProgressManager.checkCanceled()
            val file = psiManager.findFile(virtualFile) ?: return@mapNotNull null
            val offset = when (virtualFile.extension?.lowercase()) {
                "bib" -> Regex("(?m)^\\s*@[A-Za-z]+\\s*\\{\\s*${Regex.escape(key)}(?=\\s*,)").find(file.text)?.range?.last
                else -> Regex("(?m)^${Regex.escape(key)}(?=:\\s*(?:$|\\{))").find(file.text)?.range?.first
            } ?: return@mapNotNull file
            file.findElementAt(offset) ?: file
        }
    }
}
