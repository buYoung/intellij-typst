package com.livteam.typninja.language.documentation

import com.intellij.lang.ASTNode
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.livteam.typninja.language.analysis.TypstCallableSignature
import com.livteam.typninja.language.analysis.TypstSemanticModel
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

class TypstParameterInfoHandler : ParameterInfoHandler<PsiElement, TypstCallableSignature> {

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? =
        findCallAt(context.file, context.offset)

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        val signature = TypstSemanticModel.callableForCall(element.node) ?: return
        context.itemsToShow = arrayOf(signature)
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        findCallAt(context.file, context.offset)

    override fun updateParameterInfo(element: PsiElement, context: UpdateParameterInfoContext) {
        context.setCurrentParameter(argumentIndex(element.node, context.offset))
    }

    override fun updateUI(parameter: TypstCallableSignature, context: ParameterInfoUIContext) {
        val parameterIndex = context.currentParameterIndex
        val range = parameterRange(parameter, parameterIndex)
        context.setupUIComponentPresentation(
            parameter.presentation,
            range?.first ?: -1,
            range?.last?.plus(1) ?: -1,
            false,
            false,
            false,
            context.defaultParameterColor,
        )
    }

    private fun findCallAt(file: PsiElement, offset: Int): PsiElement? {
        var node: ASTNode? = file.node.findLeafElementAt(offset)
        while (node != null) {
            if (node.elementType == E.FUNC_CALL && node.textRange.contains(offset)) return node.psi
            node = node.treeParent
        }
        return null
    }


    private fun argumentIndex(call: ASTNode, offset: Int): Int {
        val args = call.findChildByType(E.ARGS) ?: return 0
        val signature = TypstSemanticModel.callableForCall(call)
        var index = 0
        var child = args.firstChildNode
        while (child != null) {
            if (child.textRange.containsOffset(offset) && child.elementType == E.NAMED) {
                val name = child.findChildByType(T.IDENTIFIER)?.text
                val namedIndex = signature?.parameters?.indexOfFirst { it.name == name } ?: -1
                if (namedIndex >= 0) return namedIndex
            }
            if (child.startOffset >= offset) return index
            if (child.elementType == T.COMMA) index++
            child = child.treeNext
        }
        return index
    }

    private fun parameterRange(signature: TypstCallableSignature, index: Int): IntRange? {
        if (index !in signature.parameters.indices) return null
        var start = signature.name.length + 1
        for (parameterIndex in 0 until index) start += signature.parameters[parameterIndex].name.length + 2
        return start until start + signature.parameters[index].name.length
    }

}
