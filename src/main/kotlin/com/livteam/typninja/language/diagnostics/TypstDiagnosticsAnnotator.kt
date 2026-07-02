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
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.psi.TypstTokenTypes as T
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
        if (isStandaloneBareBox(element)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "Standalone `box` does not produce content; call it with parentheses or a content block")
                .range(element.textRange)
                .highlightType(ProblemHighlightType.GENERIC_ERROR)
                .create()
            return
        }
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
