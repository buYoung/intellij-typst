package com.livteam.typninja.language.analysis

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.livteam.typninja.language.psi.TypstFieldAccess
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

/** Immutable callable view shared by parameter info, documentation and inlay hints. */
data class TypstCallableSignature(
    val name: String,
    val parameters: List<TypstBuiltins.Parameter>,
) {
    val presentation: String = "$name(${parameters.joinToString { it.name }})"
}

object TypstSemanticModel {
    fun callableForCall(call: ASTNode): TypstCallableSignature? {
        val callee = firstMeaningfulChild(call) ?: return null
        val definition = when (val psi = callee.psi) {
            is TypstReferenceExpression -> TypstAnalysis.resolve(psi)?.definition
            is TypstFieldAccess -> TypstAnalysis.resolveField(psi)
            else -> null
        }
        val builtinMetadata = when (val psi = callee.psi) {
            is TypstReferenceExpression -> TypstBuiltins.metadata(psi.referenceName)
            is TypstFieldAccess -> psi.qualifierName?.let { qualifier ->
                psi.memberName?.let { member -> TypstBuiltins.moduleMemberMetadata(qualifier, member) }
            }
            else -> null
        }
        builtinMetadata?.let { return TypstCallableSignature(it.name, it.parameters) }
        if (definition?.kind != TypstDefinitionKind.LET_FUNCTION) return null
        val params = definition.declarationElement.node.findChildByType(E.PARAMS) ?: return TypstCallableSignature(definition.name, emptyList())
        return TypstCallableSignature(definition.name, parameterDefinitions(params))
    }

    private fun parameterDefinitions(params: ASTNode): List<TypstBuiltins.Parameter> {
        val names = ArrayList<TypstBuiltins.Parameter>()
        var child = params.firstChildNode
        while (child != null) {
            ProgressManager.checkCanceled()
            when (child.elementType) {
                T.IDENTIFIER -> names.add(TypstBuiltins.Parameter(child.text))
                E.NAMED -> child.findChildByType(T.IDENTIFIER)?.let { names.add(TypstBuiltins.Parameter(it.text)) }
            }
            child = child.treeNext
        }
        return names
    }

    private fun firstMeaningfulChild(node: ASTNode): ASTNode? {
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType != com.intellij.psi.TokenType.WHITE_SPACE) return child
            child = child.treeNext
        }
        return null
    }
}
