package com.livteam.typninja.language.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.util.text.StringUtil
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstDefinition
import com.livteam.typninja.language.analysis.TypstDefinitionKind
import com.livteam.typninja.language.psi.TypstLetBinding
import com.livteam.typninja.language.psi.TypstRef
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.psi.TypstFieldAccess
import com.livteam.typninja.language.psi.TypstMathIdentifier
import com.livteam.typninja.language.psi.TypstBindingDeclaration
import com.livteam.typninja.language.psi.TypstTokenTypes
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.references.TypstLabelResolver
import com.livteam.typninja.language.analysis.TypstSemanticModel

class TypstDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val target = originalElement ?: element
        val referenceExpression = PsiTreeUtil.getParentOfType(target, TypstReferenceExpression::class.java, false)
        val fieldAccess = PsiTreeUtil.getParentOfType(target, TypstFieldAccess::class.java, false)
        val mathIdentifier = PsiTreeUtil.getParentOfType(target, TypstMathIdentifier::class.java, false)
        val definition = fieldAccess?.let(TypstAnalysis::resolveField)
            ?: mathIdentifier?.let { TypstAnalysis.resolveMath(it)?.definition }
            ?: referenceExpression?.let { TypstAnalysis.resolve(it)?.definition }
            ?: definitionForElement(target)
            ?: return labelDoc(target)
        val metadata = fieldAccess?.let(TypstAnalysis::fieldMetadata)
            ?: mathIdentifier?.let(::mathMetadata)
            ?: TypstBuiltins.metadata(definition.name)
        return renderDefinition(definition, metadata)
    }

    override fun getQuickNavigateInfo(element: PsiElement, originalElement: PsiElement?): String? {
        val referenceExpression = originalElement?.let {
            PsiTreeUtil.getParentOfType(it, TypstReferenceExpression::class.java, false)
        }
        val fieldAccess = originalElement?.let { PsiTreeUtil.getParentOfType(it, TypstFieldAccess::class.java, false) }
        val mathIdentifier = originalElement?.let { PsiTreeUtil.getParentOfType(it, TypstMathIdentifier::class.java, false) }
        val definition = fieldAccess?.let(TypstAnalysis::resolveField)
            ?: mathIdentifier?.let { TypstAnalysis.resolveMath(it)?.definition }
            ?: referenceExpression?.let { TypstAnalysis.resolve(it)?.definition }
            ?: definitionForElement(originalElement ?: element)
            ?: return null
        val signature = TypstSemanticModel.callableForDefinition(definition)?.presentation ?: definition.name
        return "${kindText(definition.effectiveKind)} $signature"
    }

    private fun definitionForElement(element: PsiElement): TypstDefinition? {
        val snapshot = TypstAnalysis.snapshot(element.containingFile) ?: return null
        val letBinding = PsiTreeUtil.getParentOfType(element, TypstLetBinding::class.java, false)
        if (letBinding != null) {
            return snapshot.declarations.firstOrNull { it.declarationElement == letBinding || it.nameElement == element }
        }
        val binding = PsiTreeUtil.getParentOfType(element, TypstBindingDeclaration::class.java, false)
        if (binding != null) {
            return snapshot.declarations.firstOrNull { it.navigationElement == binding || it.nameElement == binding.nameIdentifier }
        }
        return snapshot.declarations.firstOrNull { it.nameElement == element }
    }

    private fun labelDoc(element: PsiElement): String? {
        val ref = PsiTreeUtil.getParentOfType(element, TypstRef::class.java, false)
        if (ref != null && ref.referenceName.isNotEmpty()) {
            val status = if (TypstLabelResolver.resolveLabels(ref.containingFile, ref.referenceName).isNotEmpty()) "resolved" else "unresolved"
            return "<b>Typst label reference</b><br/><code>@${ref.referenceName}</code><br/>Status: $status"
        }
        val text = element.text
        if (text.startsWith("<") && text.endsWith(">")) {
            return "<b>Typst label</b><br/><code>${TypstLabelResolver.labelName(text)}</code>"
        }
        return null
    }

    private fun renderDefinition(definition: TypstDefinition, metadata: TypstBuiltins.Metadata?): String {
        val signature = metadata?.signature
            ?: TypstSemanticModel.callableForDefinition(definition)?.presentation
            ?: definition.name
        val summary = metadata?.summary
        val path = metadata?.documentationPath
        val sourceDocumentation = sourceDocumentation(definition)
        return buildString {
            append("<b>").append(kindText(definition.effectiveKind)).append("</b> ")
            append("<code>").append(StringUtil.escapeXmlEntities(signature)).append("</code>")
            if (!summary.isNullOrBlank()) append("<br/>").append(StringUtil.escapeXmlEntities(summary))
            if (!sourceDocumentation.isNullOrBlank()) append("<br/><br/>").append(StringUtil.escapeXmlEntities(sourceDocumentation).replace("\n", "<br/>"))
            if (!path.isNullOrBlank()) append("<br/><a href=\"https://typst.app").append(path).append("\">Typst reference</a>")
        }
    }

    private fun sourceDocumentation(definition: TypstDefinition): String? {
        if (definition.kind != TypstDefinitionKind.LET_FUNCTION && definition.kind != TypstDefinitionKind.LET_VARIABLE) return null
        val declaration = definition.declarationElement
        val lines = ArrayDeque<String>()
        var leaf = PsiTreeUtil.prevLeaf(declaration)
        while (leaf != null) {
            when (leaf.node.elementType) {
                TypstTokenTypes.LINE_COMMENT -> {
                    val text = leaf.text.trim()
                    if (!text.startsWith("///") && !text.startsWith("//!")) return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
                    lines.addFirst(text.removePrefix("///").removePrefix("//!").trim())
                }
                com.intellij.psi.TokenType.WHITE_SPACE -> if (leaf.text.count { it == '\n' } > 1) break
                else -> break
            }
            leaf = PsiTreeUtil.prevLeaf(leaf)
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun mathMetadata(identifier: TypstMathIdentifier): TypstBuiltins.Metadata? {
        val path = identifier.qualifiedPath
        if ('.' !in path) return TypstBuiltins.mathMetadata(path) ?: TypstBuiltins.metadata(path)
        val ownerPath = path.substringBeforeLast('.')
        val memberName = path.substringAfterLast('.')
        return TypstBuiltins.dottedMemberMetadata(ownerPath, memberName)
            ?: TypstBuiltins.dottedMemberMetadata("sym.$ownerPath", memberName)
            ?: TypstBuiltins.moduleMemberMetadata(ownerPath, memberName)
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
