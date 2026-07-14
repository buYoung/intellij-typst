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
import com.livteam.typninja.language.TypstFileType
import com.livteam.typninja.language.psi.TypstFile

class TypstLabelIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME
    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { input ->
        LABEL_PATTERN.findAll(input.contentAsText).map { it.groupValues[1] }.toSet().associateWith { null }
    }
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = 1
    override fun getInputFilter(): FileBasedIndex.InputFilter = FileBasedIndex.InputFilter { it.fileType == TypstFileType }
    override fun dependsOnFileContent(): Boolean = true

    companion object {
        val NAME: ID<String, Void> = ID.create("typst.labels")
        private val LABEL_PATTERN = Regex("<([\\p{L}_][\\p{L}\\p{N}_:.-]*)>")
    }
}

object TypstProjectLabels {
    fun names(project: Project): Collection<String> =
        if (DumbService.isDumb(project)) emptyList()
        else FileBasedIndex.getInstance().getAllKeys(TypstLabelIndex.NAME, project)

    fun definitions(project: Project, name: String, scope: GlobalSearchScope = GlobalSearchScope.projectScope(project)): List<PsiElement> {
        if (DumbService.isDumb(project)) return emptyList()
        val psiManager = PsiManager.getInstance(project)
        return FileBasedIndex.getInstance().getContainingFiles(TypstLabelIndex.NAME, name, scope).mapNotNull { virtualFile ->
            ProgressManager.checkCanceled()
            val file = psiManager.findFile(virtualFile) as? TypstFile ?: return@mapNotNull null
            TypstAnalysis.snapshot(file)?.labelDefinition(name)?.nameElement
        }
    }
}
