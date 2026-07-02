package com.livteam.typninja.language.documentation

import com.intellij.lang.ASTNode
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstDefinitionKind
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

class TypstParameterInfoHandler : ParameterInfoHandler<PsiElement, String> {

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? =
        findCallAt(context.file, context.offset)

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        val signature = signatureForCall(element.node) ?: return
        context.itemsToShow = arrayOf(signature)
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        findCallAt(context.file, context.offset)

    override fun updateParameterInfo(element: PsiElement, context: UpdateParameterInfoContext) {
        context.setCurrentParameter(argumentIndex(element.node, context.offset))
    }

    override fun updateUI(parameter: String, context: ParameterInfoUIContext) {
        context.setupUIComponentPresentation(parameter, 0, parameter.length, false, false, false, context.defaultParameterColor)
    }

    private fun findCallAt(file: PsiElement, offset: Int): PsiElement? {
        var node: ASTNode? = file.node.findLeafElementAt(offset)
        while (node != null) {
            if (node.elementType == E.FUNC_CALL && node.textRange.contains(offset)) return node.psi
            node = node.treeParent
        }
        return null
    }

    private fun signatureForCall(call: ASTNode): String? {
        val calleeName = calleeName(call) ?: return null
        TypstBuiltins.metadata(calleeName)?.signature?.let { return it }
        val firstChild = firstMeaningfulChild(call) ?: return null
        val referenceExpression = firstChild.psi as? com.livteam.typninja.language.psi.TypstReferenceExpression ?: return null
        val definition = TypstAnalysis.resolve(referenceExpression)?.definition ?: return null
        if (definition.kind != TypstDefinitionKind.LET_FUNCTION) return null
        val params = definition.declarationElement.node.findChildByType(E.PARAMS) ?: return definition.name
        return "${definition.name}(${parameterNames(params).joinToString()})"
    }

    private fun calleeName(call: ASTNode): String? {
        val callee = firstMeaningfulChild(call) ?: return null
        return when (callee.elementType) {
            T.IDENTIFIER -> callee.text
            E.REFERENCE_EXPR -> callee.findChildByType(T.IDENTIFIER)?.text
            E.FIELD_ACCESS -> lastIdentifier(callee)?.text
            else -> null
        }
    }

    private fun parameterNames(params: ASTNode): List<String> {
        val names = ArrayList<String>()
        var child = params.firstChildNode
        while (child != null) {
            ProgressManager.checkCanceled()
            when (child.elementType) {
                T.IDENTIFIER -> names.add(child.text)
                E.NAMED -> child.findChildByType(T.IDENTIFIER)?.let { names.add(it.text) }
            }
            child = child.treeNext
        }
        return names
    }

    private fun argumentIndex(call: ASTNode, offset: Int): Int {
        val args = call.findChildByType(E.ARGS) ?: return 0
        var index = 0
        var child = args.firstChildNode
        while (child != null) {
            if (child.startOffset >= offset) return index
            if (child.elementType == T.COMMA) index++
            child = child.treeNext
        }
        return index
    }

    private fun firstMeaningfulChild(node: ASTNode): ASTNode? {
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType != com.intellij.psi.TokenType.WHITE_SPACE) return child
            child = child.treeNext
        }
        return null
    }

    private fun lastIdentifier(node: ASTNode): ASTNode? {
        var result: ASTNode? = null
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType == T.IDENTIFIER) result = child
            child = child.treeNext
        }
        return result
    }
}
