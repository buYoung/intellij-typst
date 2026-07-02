package com.livteam.typninja.language.editor

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.references.TypstBuiltins.Parameter
import com.livteam.typninja.language.references.TypstBuiltins
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

class TypstInlayHintsProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file !is TypstFile) return null
        return TypstInlayHintsCollector
    }
}

private object TypstInlayHintsCollector : SharedBypassCollector {

    override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
        val node = element.node ?: return
        val parameterName = TypstInlayHintLogic.parameterNameForArgument(node) ?: return
        sink.addPresentation(
            InlineInlayPosition(node.startOffset, true, 0),
            emptyList<InlayPayload>(),
            "Typst parameter",
            false,
        ) {
            text("$parameterName:", null)
        }
    }
}

internal object TypstInlayHintLogic {

    fun parameterNameForArgument(argument: com.intellij.lang.ASTNode): String? {
        val args = argument.treeParent ?: return null
        if (args.elementType != E.ARGS || !isPositionalArgument(argument)) return null
        if (argument.elementType == T.LPAREN || argument.elementType == T.RPAREN) return null
        val call = args.treeParent ?: return null
        if (call.elementType != E.FUNC_CALL) return null
        val calleeName = calleeName(call) ?: return null
        val metadata = TypstBuiltins.metadata(calleeName) ?: return null
        return positionalParameter(metadata.parameters, args, argument)?.name
    }

    private fun positionalParameter(
        parameters: List<Parameter>,
        args: com.intellij.lang.ASTNode,
        argument: com.intellij.lang.ASTNode,
    ): Parameter? {
        val namedArguments = namedArgumentNames(args)
        var positionalIndex = 0
        var child = args.firstChildNode
        while (child != null && child !== argument) {
            if (isPositionalArgument(child)) positionalIndex++
            child = child.treeNext
        }
        return parameters.filterNot { it.name in namedArguments }.getOrNull(positionalIndex)
    }

    private fun namedArgumentNames(args: com.intellij.lang.ASTNode): Set<String> {
        val names = LinkedHashSet<String>()
        var child = args.firstChildNode
        while (child != null) {
            if (child.elementType == E.NAMED) {
                child.findChildByType(T.IDENTIFIER)?.text?.let { names.add(it) }
            }
            child = child.treeNext
        }
        return names
    }

    private fun isPositionalArgument(node: com.intellij.lang.ASTNode): Boolean =
        node.elementType != T.LPAREN &&
            node.elementType != T.RPAREN &&
            node.elementType != T.COMMA &&
            node.elementType != E.NAMED &&
            node.elementType != TokenType.WHITE_SPACE &&
            node.elementType != T.LINE_COMMENT &&
            node.elementType != T.BLOCK_COMMENT &&
            node.elementType != T.PARBREAK

    private fun calleeName(call: com.intellij.lang.ASTNode): String? {
        var child = call.firstChildNode
        while (child != null && child.elementType == com.intellij.psi.TokenType.WHITE_SPACE) child = child.treeNext
        return when (child?.elementType) {
            T.IDENTIFIER -> child.text
            E.REFERENCE_EXPR -> child.findChildByType(T.IDENTIFIER)?.text
            E.FIELD_ACCESS -> {
                var current = child.firstChildNode
                var last: String? = null
                while (current != null) {
                    if (current.elementType == T.IDENTIFIER) last = current.text
                    current = current.treeNext
                }
                last
            }
            else -> null
        }
    }
}
