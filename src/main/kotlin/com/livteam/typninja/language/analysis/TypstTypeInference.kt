package com.livteam.typninja.language.analysis

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.psi.TypstFieldAccess
import com.livteam.typninja.language.psi.TypstLetBinding
import com.livteam.typninja.language.psi.TypstNamedArgument
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.psi.TypstMathIdentifier
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

/** Small, safe type inference used only by IntelliJ's Type Declaration action. */
object TypstTypeInference {

    fun typeName(element: PsiElement): String? = typeName(element.node, LinkedHashSet())

    private fun typeName(node: ASTNode?, visitingElements: MutableSet<PsiElement>): String? {
        node ?: return null
        ProgressManager.checkCanceled()
        val psi = node.psi
        if (!visitingElements.add(psi)) return null
        try {
            return when (node.elementType) {
                T.INTEGER_LITERAL -> "int"
                T.FLOAT_LITERAL -> "float"
                T.NUMERIC_LITERAL -> numericType(node.text)
                T.STRING, E.STRING_LITERAL -> "str"
                T.TRUE, T.FALSE -> "bool"
                E.ARRAY -> "array"
                E.DICT -> "dictionary"
                E.CONTENT_BLOCK, E.MARKUP, E.HEADING, E.STRONG, E.EMPH -> "content"
                E.CLOSURE -> "function"
                E.NAMED -> namedArgumentType(psi as? TypstNamedArgument)
                    ?: childAfterColon(node)?.let { typeName(it, visitingElements) }
                E.REFERENCE_EXPR -> referenceType(psi as? TypstReferenceExpression, visitingElements)
                E.FIELD_ACCESS -> fieldType(psi as? TypstFieldAccess, visitingElements)
                E.MATH_REFERENCE -> mathReferenceType(psi as? TypstMathIdentifier)
                E.FUNC_CALL -> callType(node, visitingElements)
                E.LET_BINDING -> letType(psi as? TypstLetBinding, visitingElements)
                E.CODE_BLOCK -> lastTypedChild(node, visitingElements)
                E.PARENTHESIZED, E.CODE_EXPRESSION, E.UNARY -> firstTypedChild(node, visitingElements)
                E.BINARY -> firstTypedChild(node, visitingElements)
                else -> {
                    val reference = PsiTreeUtil.getParentOfType(psi, TypstReferenceExpression::class.java, false)
                    when {
                        reference != null -> referenceType(reference, visitingElements)
                        PsiTreeUtil.getParentOfType(psi, TypstLetBinding::class.java, false) != null -> {
                            val binding = PsiTreeUtil.getParentOfType(psi, TypstLetBinding::class.java, false)
                            letType(binding, visitingElements)
                        }
                        else -> typeName(node.treeParent, visitingElements)
                    }
                }
            }
        } finally {
            visitingElements.remove(psi)
        }
    }

    private fun referenceType(
        reference: TypstReferenceExpression?,
        visitingElements: MutableSet<PsiElement>,
    ): String? {
        reference ?: return null
        val definition = TypstAnalysis.resolve(reference)?.definition ?: return null
        return when (definition.effectiveKind) {
            TypstDefinitionKind.LET_FUNCTION, TypstDefinitionKind.BUILTIN_FUNCTION -> "function"
            TypstDefinitionKind.BUILTIN_TYPE -> "type"
            TypstDefinitionKind.BUILTIN_MODULE, TypstDefinitionKind.MODULE_ALIAS -> "module"
            TypstDefinitionKind.BUILTIN_VALUE -> TypstBuiltins.metadata(definition.name)?.returnType
            TypstDefinitionKind.LET_VARIABLE,
            TypstDefinitionKind.PARAMETER,
            TypstDefinitionKind.LOOP_BINDING,
            TypstDefinitionKind.IMPORTED_SYMBOL -> typeName(definition.navigationElement.node, visitingElements)
            TypstDefinitionKind.LABEL -> "label"
        }
    }

    private fun fieldType(fieldAccess: TypstFieldAccess?, visitingElements: MutableSet<PsiElement>): String? {
        fieldAccess ?: return null
        val memberMetadata = TypstAnalysis.fieldMetadata(fieldAccess)
        if (memberMetadata != null) {
            return if (memberMetadata.kind == TypstBuiltins.Kind.FUNCTION) "function" else memberMetadata.returnType
        }
        TypstAnalysis.fieldValueElement(fieldAccess)?.let { typeName(it.node, visitingElements) }?.let { return it }
        return when (TypstAnalysis.resolveField(fieldAccess)?.effectiveKind) {
            TypstDefinitionKind.LET_FUNCTION, TypstDefinitionKind.BUILTIN_FUNCTION -> "function"
            TypstDefinitionKind.BUILTIN_TYPE -> "type"
            TypstDefinitionKind.BUILTIN_MODULE, TypstDefinitionKind.MODULE_ALIAS -> "module"
            else -> null
        }
    }

    private fun mathReferenceType(identifier: TypstMathIdentifier?): String? {
        identifier ?: return null
        val definition = TypstAnalysis.resolveMath(identifier)?.definition ?: return null
        return when (definition.effectiveKind) {
            TypstDefinitionKind.LET_FUNCTION, TypstDefinitionKind.BUILTIN_FUNCTION -> "function"
            TypstDefinitionKind.BUILTIN_TYPE -> "type"
            TypstDefinitionKind.BUILTIN_MODULE, TypstDefinitionKind.MODULE_ALIAS -> "module"
            TypstDefinitionKind.BUILTIN_VALUE -> "symbol"
            TypstDefinitionKind.LET_VARIABLE,
            TypstDefinitionKind.PARAMETER,
            TypstDefinitionKind.LOOP_BINDING,
            TypstDefinitionKind.IMPORTED_SYMBOL -> null
            TypstDefinitionKind.LABEL -> "label"
        }
    }

    private fun callType(call: ASTNode, visitingElements: MutableSet<PsiElement>): String? {
        val signature = TypstSemanticModel.callableForCall(call)
        signature?.builtinMetadata?.returnType?.let { return it }
        val definition = signature?.definition ?: return null
        val declaration = PsiTreeUtil.getParentOfType(definition.navigationElement, TypstLetBinding::class.java, false)
            ?: definition.declarationElement as? TypstLetBinding
            ?: return null
        return letResultNode(declaration.node)?.let { typeName(it, visitingElements) }
    }

    private fun namedArgumentType(namedArgument: TypstNamedArgument?): String? {
        namedArgument ?: return null
        if (namedArgument.node.treeParent?.elementType != E.ARGS) return null
        val name = namedArgument.argumentName ?: return null
        val call = generateSequence(namedArgument.node.treeParent) { it.treeParent }
            .firstOrNull { it.elementType == E.FUNC_CALL }
            ?: return null
        return TypstSemanticModel.callableForCall(call)?.parameters?.firstOrNull { it.name == name }?.typeName
    }

    private fun letType(binding: TypstLetBinding?, visitingElements: MutableSet<PsiElement>): String? {
        binding ?: return null
        if (binding.node.findChildByType(E.PARAMS) != null) return "function"
        return letResultNode(binding.node)?.let { typeName(it, visitingElements) }
    }

    private fun letResultNode(binding: ASTNode): ASTNode? {
        var child = binding.firstChildNode
        var sawEquals = false
        while (child != null) {
            if (child.elementType == T.EQ) {
                sawEquals = true
            } else if (sawEquals && !isSkippable(child)) {
                return child
            }
            child = child.treeNext
        }
        return null
    }

    private fun childAfterColon(node: ASTNode): ASTNode? {
        var child = node.firstChildNode
        var sawColon = false
        while (child != null) {
            if (child.elementType == T.COLON) sawColon = true
            else if (sawColon && !isSkippable(child)) return child
            child = child.treeNext
        }
        return null
    }

    private fun firstTypedChild(node: ASTNode, visitingElements: MutableSet<PsiElement>): String? {
        var child = node.firstChildNode
        while (child != null) {
            if (!isSkippable(child)) typeName(child, visitingElements)?.let { return it }
            child = child.treeNext
        }
        return null
    }

    private fun lastTypedChild(node: ASTNode, visitingElements: MutableSet<PsiElement>): String? {
        var child = node.lastChildNode
        while (child != null) {
            if (!isSkippable(child)) typeName(child, visitingElements)?.let { return it }
            child = child.treePrev
        }
        return null
    }

    private fun numericType(text: String): String = when {
        text.endsWith('%') -> "ratio"
        text.endsWith("deg") || text.endsWith("rad") -> "angle"
        text.endsWith("fr") -> "fraction"
        else -> "length"
    }

    private fun isSkippable(node: ASTNode): Boolean = when (node.elementType) {
        TokenType.WHITE_SPACE, T.LINE_COMMENT, T.BLOCK_COMMENT, T.PARBREAK,
        T.HASH, T.EQ, T.LPAREN, T.RPAREN, T.COMMA -> true
        else -> false
    }
}
