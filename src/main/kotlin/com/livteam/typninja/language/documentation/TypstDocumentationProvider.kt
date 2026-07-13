package com.livteam.typninja.language.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstDefinition
import com.livteam.typninja.language.analysis.TypstDefinitionKind
import com.livteam.typninja.language.psi.TypstLetBinding
import com.livteam.typninja.language.psi.TypstRef
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.references.TypstLabelResolver

class TypstDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val target = originalElement ?: element
        val referenceExpression = PsiTreeUtil.getParentOfType(target, TypstReferenceExpression::class.java, false)
        val definition = referenceExpression?.let { TypstAnalysis.resolve(it)?.definition }
            ?: definitionForElement(target)
            ?: return labelDoc(target)
        return renderDefinition(definition)
    }

    override fun getQuickNavigateInfo(element: PsiElement, originalElement: PsiElement?): String? {
        val referenceExpression = originalElement?.let {
            PsiTreeUtil.getParentOfType(it, TypstReferenceExpression::class.java, false)
        }
        val definition = referenceExpression?.let { TypstAnalysis.resolve(it)?.definition }
            ?: definitionForElement(originalElement ?: element)
            ?: return null
        return "${kindText(definition.kind)} ${definition.name}"
    }

    private fun definitionForElement(element: PsiElement): TypstDefinition? {
        val snapshot = TypstAnalysis.snapshot(element.containingFile) ?: return null
        val letBinding = PsiTreeUtil.getParentOfType(element, TypstLetBinding::class.java, false)
        if (letBinding != null) {
            return snapshot.declarations.firstOrNull { it.declarationElement == letBinding || it.nameElement == element }
        }
        return snapshot.declarations.firstOrNull { it.nameElement == element }
    }

    private fun labelDoc(element: PsiElement): String? {
        val ref = PsiTreeUtil.getParentOfType(element, TypstRef::class.java, false)
        if (ref != null && ref.referenceName.isNotEmpty()) {
            val status = if (ref.reference?.resolve() != null) "resolved" else "unresolved"
            return "<b>Typst label reference</b><br/><code>@${ref.referenceName}</code><br/>Status: $status"
        }
        val text = element.text
        if (text.startsWith("<") && text.endsWith(">")) {
            return "<b>Typst label</b><br/><code>${TypstLabelResolver.labelName(text)}</code>"
        }
        return null
    }

    private fun renderDefinition(definition: TypstDefinition): String {
        val metadata = TypstBuiltins.metadata(definition.name)
        val signature = metadata?.signature ?: definition.name
        val summary = metadata?.summary
        val path = metadata?.documentationPath
        return buildString {
            append("<b>").append(kindText(definition.kind)).append("</b> ")
            append("<code>").append(signature).append("</code>")
            if (!summary.isNullOrBlank()) append("<br/>").append(summary)
            if (!path.isNullOrBlank()) append("<br/><a href=\"https://typst.app").append(path).append("\">Typst reference</a>")
        }
    }

    private fun kindText(kind: TypstDefinitionKind): String = when (kind) {
        TypstDefinitionKind.LET_VARIABLE -> "variable"
        TypstDefinitionKind.LET_FUNCTION -> "function"
        TypstDefinitionKind.PARAMETER -> "parameter"
        TypstDefinitionKind.LOOP_BINDING -> "loop binding"
        TypstDefinitionKind.LABEL -> "label"
        TypstDefinitionKind.IMPORTED_SYMBOL -> "imported symbol"
        TypstDefinitionKind.MODULE_ALIAS -> "module"
        TypstDefinitionKind.BUILTIN_FUNCTION -> "builtin function"
        TypstDefinitionKind.BUILTIN_TYPE -> "builtin type"
        TypstDefinitionKind.BUILTIN_MODULE -> "builtin module"
        TypstDefinitionKind.BUILTIN_VALUE -> "builtin value"
    }
}
