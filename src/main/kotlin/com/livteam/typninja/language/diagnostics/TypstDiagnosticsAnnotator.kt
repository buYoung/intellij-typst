package com.livteam.typninja.language.diagnostics

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiElement
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstModuleFiles
import com.livteam.typninja.language.psi.TypstModuleImport
import com.livteam.typninja.language.psi.TypstRef
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.psi.TypstElementTypes as E

class TypstDiagnosticsAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is TypstReferenceExpression -> annotateReference(element, holder)
            is TypstRef -> annotateLabelReference(element, holder)
            is TypstModuleImport -> annotateImport(element, holder)
            else -> if (element.node.elementType == E.MODULE_INCLUDE) annotateInclude(element, holder)
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

    private fun annotateLabelReference(element: TypstRef, holder: AnnotationHolder) {
        if (element.referenceName.isEmpty() || element.reference?.resolve() != null) return
        holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Unresolved Typst label")
            .range(element.textRange)
            .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
            .create()
    }

    private fun annotateImport(element: TypstModuleImport, holder: AnnotationHolder) {
        val summary = TypstAnalysis.parseImport(element.node)
        val path = summary.pathString ?: return
        val pathElement = element.node.findChildByType(E.STRING_LITERAL)?.psi ?: element
        if (summary.isPackageImport) {
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Typst package imports are not supported yet")
                .range(pathElement.textRange)
                .create()
            return
        }
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
        if (path.startsWith("@")) {
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Typst package includes are not supported yet")
                .range(pathNode.psi.textRange)
                .create()
            return
        }
        if (TypstModuleFiles.resolveModuleFile(element.containingFile, path) == null) {
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Unresolved Typst include")
                .range(pathNode.psi.textRange)
                .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
                .create()
        }
    }
}
