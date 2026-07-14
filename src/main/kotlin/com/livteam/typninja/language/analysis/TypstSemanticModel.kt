package com.livteam.typninja.language.analysis

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.psi.TypstFieldAccess
import com.livteam.typninja.language.psi.TypstLetBinding
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.psi.TypstBindingDeclaration
import com.livteam.typninja.language.references.TypstBuiltinResolver
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

/** Immutable callable view shared by parameter info, documentation and inlay hints. */
data class TypstCallableSignature(
    val name: String,
    val parameters: List<TypstBuiltins.Parameter>,
    val isParameterListComplete: Boolean,
    val definition: TypstDefinition? = null,
    val builtinMetadata: TypstBuiltins.Metadata? = null,
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
            is TypstFieldAccess -> TypstAnalysis.fieldMetadata(psi)
            else -> null
        }
        builtinMetadata?.let {
            return TypstCallableSignature(
                name = it.name,
                parameters = it.parameters,
                isParameterListComplete = it.isParameterListComplete,
                definition = definition,
                builtinMetadata = it,
            )
        }
        if (definition?.effectiveKind != TypstDefinitionKind.LET_FUNCTION) return null
        return callableForDefinition(definition)
    }

    fun callableForDefinition(definition: TypstDefinition): TypstCallableSignature? {
        if (definition.effectiveKind != TypstDefinitionKind.LET_FUNCTION) return null
        val declaration = PsiTreeUtil.getParentOfType(
            definition.navigationElement,
            TypstLetBinding::class.java,
            false,
        ) ?: definition.declarationElement as? TypstLetBinding
        val params = declaration?.node?.findChildByType(E.PARAMS)
            ?: return TypstCallableSignature(definition.name, emptyList(), true, definition)
        return TypstCallableSignature(definition.name, parameterDefinitions(params), true, definition)
    }

    fun parameterTarget(call: ASTNode, parameterName: String): PsiElement? {
        val signature = callableForCall(call) ?: return null
        if (signature.parameters.none { it.name == parameterName }) return null
        val declarationTarget = signature.builtinMetadata?.let {
            TypstBuiltinResolver.resolve(call.psi.project, it.name)
        } ?: signature.definition?.navigationElement
        val letBinding = PsiTreeUtil.getParentOfType(declarationTarget, TypstLetBinding::class.java, false)
            ?: declarationTarget as? TypstLetBinding
            ?: return declarationTarget
        val params = letBinding.node.findChildByType(E.PARAMS) ?: return declarationTarget
        return parameterNameNode(params, parameterName)?.psi ?: declarationTarget
    }

    private fun parameterDefinitions(params: ASTNode): List<TypstBuiltins.Parameter> {
        val names = ArrayList<TypstBuiltins.Parameter>()
        collectParameterDefinitions(params, names)
        return names
    }

    private fun collectParameterDefinitions(node: ASTNode, output: MutableList<TypstBuiltins.Parameter>) {
        var child = node.firstChildNode
        while (child != null) {
            ProgressManager.checkCanceled()
            if (child.elementType == E.BINDING_DECLARATION) {
                (child.psi as? TypstBindingDeclaration)?.name?.let { output.add(TypstBuiltins.Parameter(it)) }
            } else if (child.elementType == E.DESTRUCTURING || child.elementType == E.NAMED || child.elementType == E.SPREAD) {
                collectParameterDefinitions(child, output)
            }
            child = child.treeNext
        }
    }

    private fun parameterNameNode(params: ASTNode, name: String): ASTNode? {
        return generateSequence(params.firstChildNode) { nextDepthFirst(it, params) }
            .firstOrNull { it.elementType == E.BINDING_DECLARATION && (it.psi as? TypstBindingDeclaration)?.name == name }
            ?.findChildByType(T.IDENTIFIER)
    }

    private fun nextDepthFirst(node: ASTNode, root: ASTNode): ASTNode? {
        node.firstChildNode?.let { return it }
        var current: ASTNode? = node
        while (current != null && current !== root) {
            current.treeNext?.let { return it }
            current = current.treeParent
        }
        return null
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
