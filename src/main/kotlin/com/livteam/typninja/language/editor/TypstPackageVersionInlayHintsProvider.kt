package com.livteam.typninja.language.editor

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstProjectModelService
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstModuleImport
import com.livteam.typninja.language.references.TypstPackageResolver
import com.livteam.typninja.settings.TypstSettingsService

class TypstPackageVersionInlayHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file !is TypstFile) return null
        val settings = TypstSettingsService.getInstance(file.project).state
        if (settings.syntaxOnlyMode || !settings.showPackageVersionHints) return null
        return TypstPackageVersionInlayHintsCollector
    }
}

private object TypstPackageVersionInlayHintsCollector : SharedBypassCollector {
    override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
        val moduleImport = element as? TypstModuleImport ?: return
        val specification = TypstAnalysis.parseImport(moduleImport.node).pathString ?: return
        val requested = TypstPackageResolver.parse(specification) ?: return
        val installed = TypstProjectModelService.getInstance(element.project).packageCatalog().specifications()
            .mapNotNull(TypstPackageResolver::parse)
            .filter { it.namespace == requested.namespace && it.name == requested.name }
        val exactInstalled = installed.any { it.version == requested.version }
        val newest = installed.maxWithOrNull(compareByVersion())
        val message = when {
            exactInstalled && newest != null && compareVersions(newest.version, requested.version) > 0 -> "installed · ${newest.version} available"
            exactInstalled -> "installed"
            newest != null -> "not installed · ${newest.version} is available locally"
            else -> "not installed"
        }
        sink.addPresentation(
            InlineInlayPosition(moduleImport.textRange.endOffset, true, 0),
            emptyList<InlayPayload>(),
            "Typst package version",
            false,
        ) {
            text("  $message", null)
        }
    }

    private fun compareByVersion(): Comparator<com.livteam.typninja.language.references.TypstPackageSpec> =
        Comparator { left, right -> compareVersions(left.version, right.version) }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val difference = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
            if (difference != 0) return difference
        }
        return left.compareTo(right)
    }
}
