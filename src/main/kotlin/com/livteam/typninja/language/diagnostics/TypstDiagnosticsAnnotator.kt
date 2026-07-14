package com.livteam.typninja.language.diagnostics

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstDiagnosticCategory
import com.livteam.typninja.language.analysis.TypstDiagnosticEngine
import com.livteam.typninja.language.actions.TypstTextEditIntention
import com.livteam.typninja.language.analysis.TypstModuleFiles
import com.livteam.typninja.language.psi.TypstModuleImport
import com.livteam.typninja.language.psi.TypstRef
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.references.TypstLabelResolver
import com.livteam.typninja.settings.TypstSettingsService
import com.livteam.typninja.language.psi.TypstTokenTypes as T
import com.livteam.typninja.language.psi.TypstElementTypes as E

class TypstDiagnosticsAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val settings = TypstSettingsService.getInstance(element.project).state
        if (settings.syntaxOnlyMode) return
        if (!settings.enableSemanticDiagnostics && !settings.enableLint) return
        TypstDiagnosticEngine.diagnosticsFor(element).forEach { diagnostic ->
            val enabled = diagnostic.category == TypstDiagnosticCategory.SEMANTIC && settings.enableSemanticDiagnostics ||
                diagnostic.category == TypstDiagnosticCategory.LINT && settings.enableLint
            if (enabled) {
                val builder = holder.newAnnotation(diagnostic.severity, diagnostic.message).range(diagnostic.range)
                diagnostic.fix?.let { fix ->
                    builder.withFix(TypstTextEditIntention(fix.text, element, fix.range, fix.replacement))
                }
                builder.create()
            }
        }
        when (element) {
            is TypstReferenceExpression -> {
                if (settings.enableLint) annotateStandaloneBareBox(element, holder)
                if (settings.enableSemanticDiagnostics) annotateReference(element, holder)
            }
            is TypstRef -> if (settings.enableSemanticDiagnostics) annotateLabelReference(element, holder)
            is TypstModuleImport -> if (settings.enableSemanticDiagnostics) annotateImport(element, holder)
            else -> if (settings.enableSemanticDiagnostics && element.node.elementType == E.MODULE_INCLUDE) annotateInclude(element, holder)
        }
    }

    private fun annotateReference(element: TypstReferenceExpression, holder: AnnotationHolder) {
        val name = element.referenceName
        if (name.isEmpty() || TypstAnalysis.resolve(element) != null) return
        holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Unresolved Typst reference")
            .range(element.textRange)
            .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
            .create()
    }

    private fun annotateStandaloneBareBox(element: TypstReferenceExpression, holder: AnnotationHolder) {
        if (!isStandaloneBareBox(element)) return
        holder.newAnnotation(HighlightSeverity.ERROR, "Standalone `box` does not produce content; call it with parentheses or a content block")
            .range(element.textRange)
            .highlightType(ProblemHighlightType.GENERIC_ERROR)
            .withFix(TypstTextEditIntention("Call `box`", element, element.textRange, "box()"))
            .create()
    }

    private fun annotateLabelReference(element: TypstRef, holder: AnnotationHolder) {
        if (element.referenceName.isEmpty() || TypstLabelResolver.resolveLabels(element.containingFile, element.referenceName).isNotEmpty()) return
        holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Unresolved Typst label")
            .range(element.textRange)
            .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
            .create()
    }

    private fun annotateImport(element: TypstModuleImport, holder: AnnotationHolder) {
        val summary = TypstAnalysis.parseImport(element.node)
        val path = summary.pathString ?: return
        val pathElement = element.node.findChildByType(E.STRING_LITERAL)?.psi ?: element
        // A package catalog is intentionally local-only and refreshes asynchronously.  Until a
        // package is explicitly present in that catalog, absence must not be reported as a typo:
        // it could simply be an allowed registry package that is not installed on this machine.
        if (summary.isPackageImport) return
        if (TypstModuleFiles.resolveModuleFile(element.containingFile, path) == null) {
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Unresolved Typst import")
                .range(pathElement.textRange)
                .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
                .create()
        }
    }

    private fun annotateInclude(element: PsiElement, holder: AnnotationHolder) {
        val pathNode = element.node.findChildByType(E.STRING_LITERAL) ?: return
        val path = pathNode.text.trim('"')
        if (path.isEmpty()) return
        // Package catalogs refresh in the background. As with package imports, a missing local
        // cache entry must not be shown as an error because Typst may fetch the package later.
        if (path.startsWith("@")) return
        if (TypstModuleFiles.resolveModuleFile(element.containingFile, path) == null) {
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Unresolved Typst include")
                .range(pathNode.psi.textRange)
                .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
                .create()
        }
    }

    private fun isStandaloneBareBox(element: TypstReferenceExpression): Boolean {
        if (element.referenceName != "box" || !TypstBuiltins.isFunction("box")) return false
        val referenceNode = element.node
        val codeExpression = referenceNode.treeParent
        if (codeExpression?.elementType == E.CODE_EXPRESSION) {
            if (onlyMeaningfulChild(codeExpression) !== referenceNode) return false
            return codeExpression.treeParent?.elementType == E.CODE_BLOCK
        }
        return codeExpression?.elementType == E.CODE_BLOCK
    }

    private fun onlyMeaningfulChild(node: com.intellij.lang.ASTNode): com.intellij.lang.ASTNode? {
        var result: com.intellij.lang.ASTNode? = null
        var child = node.firstChildNode
        while (child != null) {
            if (!isSkippable(child.elementType)) {
                if (result != null) return null
                result = child
            }
            child = child.treeNext
        }
        return result
    }

    private fun isSkippable(type: com.intellij.psi.tree.IElementType): Boolean =
        type == com.intellij.psi.TokenType.WHITE_SPACE ||
            type == T.LINE_COMMENT ||
            type == T.BLOCK_COMMENT ||
            type == T.PARBREAK
}
