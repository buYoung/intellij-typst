package com.livteam.typninja.language.references

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstModuleFiles
import com.livteam.typninja.language.psi.TypstFile

/**
 * Cross-file resolution facade for relative `#import` statements.
 *
 * Import parsing and file-local export collection are owned by the shared analysis snapshot. This
 * object keeps the old public entry points used by PSI references while preventing each caller from
 * recursively walking the file tree independently.
 */
object TypstModuleResolver {

    data class ImportItem(val localName: String, val moduleName: String, val nameNode: ASTNode)

    data class ImportInfo(val pathString: String?, val items: List<ImportItem>, val glob: Boolean)

    fun parseImport(moduleImport: ASTNode): ImportInfo {
        val summary = TypstAnalysis.parseImport(moduleImport)
        return ImportInfo(
            pathString = summary.pathString,
            items = summary.items.map { ImportItem(it.localName, it.moduleName, it.nameNode) },
            glob = summary.isGlob,
        )
    }

    fun importsIn(file: PsiFile): List<Pair<ASTNode, ImportInfo>> =
        TypstAnalysis.snapshot(file)?.imports.orEmpty().map { it.node to parseImport(it.node) }

    fun resolveModuleFile(importingFile: PsiFile, pathString: String?): TypstFile? =
        TypstModuleFiles.resolveModuleFile(importingFile, pathString)

    fun findExport(moduleFile: TypstFile, name: String): PsiElement? =
        TypstAnalysis.exportedDefinition(moduleFile, name)?.navigationElement

    fun findImportMember(moduleFile: TypstFile, sourceSegments: List<String>, segmentIndex: Int): PsiElement? {
        return TypstAnalysis.exportPathDefinition(moduleFile, sourceSegments, segmentIndex)?.navigationElement
    }

    fun resolveImportedName(usageFile: PsiFile, name: String): PsiElement? =
        TypstAnalysis.resolveImportedName(usageFile, name)?.definition?.nameElement
}
