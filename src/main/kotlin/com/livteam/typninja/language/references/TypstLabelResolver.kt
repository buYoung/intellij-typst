package com.livteam.typninja.language.references

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.psi.TypstTokenTypes as T
import com.livteam.typninja.language.analysis.TypstProjectLabels
import com.livteam.typninja.language.analysis.TypstProjectBibliography

/**
 * File-local resolution between a `@reference` and its `<label>` definition.
 *
 * A Typst `<name>` label ([T.LABEL_DEF], text `<name>`) is the definition; a `@name` reference
 * ([T.REF_MARKER], text `@name`) points at it. Both live in markup and are plain leaves, so this
 * resolver scans the whole file for the matching [T.LABEL_DEF] leaf and returns it as the navigation
 * target. The first matching label in the file wins (Typst labels are expected to be unique; if not,
 * the first is a stable, useful target).
 */
object TypstLabelResolver {

    /** The inner name of a `@ref` marker: `@intro` -> `intro`. Empty for a lone `@`. */
    fun referenceName(refMarkerText: String): String =
        if (refMarkerText.startsWith("@")) refMarkerText.substring(1) else refMarkerText

    /** The inner name of a `<label>` definition: `<intro>` -> `intro`. */
    fun labelName(labelDefText: String): String =
        labelDefText.removePrefix("<").removeSuffix(">")

    /** The [T.LABEL_DEF] leaf in [file] whose inner name equals [name], or `null` if none defines it. */
    fun resolveLabel(file: PsiFile, name: String): PsiElement? {
        return resolveLabels(file, name).singleOrNull()
    }

    fun resolveLabels(file: PsiFile, name: String): List<PsiElement> {
        if (name.isEmpty()) return emptyList()
        val root = file.node ?: return emptyList()
        val local = findLabel(root, name)?.psi
        val definitions = ArrayList<PsiElement>()
        if (local != null) definitions.add(local)
        TypstProjectLabels.definitions(file.project, name).forEach { candidate ->
            if (definitions.none { it.manager.areElementsEquivalent(it, candidate) }) definitions.add(candidate)
        }
        TypstProjectBibliography.definitions(file.project, name).forEach { candidate ->
            if (definitions.none { it.manager.areElementsEquivalent(it, candidate) }) definitions.add(candidate)
        }
        return definitions
    }

    private fun findLabel(node: ASTNode, name: String): ASTNode? {
        if (node.elementType == T.LABEL_DEF && labelName(node.text) == name) return node
        var child = node.firstChildNode
        while (child != null) {
            ProgressManager.checkCanceled()
            findLabel(child, name)?.let { return it }
            child = child.treeNext
        }
        return null
    }
}
