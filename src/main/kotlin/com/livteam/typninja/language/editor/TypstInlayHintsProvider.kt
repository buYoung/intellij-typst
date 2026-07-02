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
import com.livteam.typninja.language.psi.TypstFile
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
        val args = node.treeParent ?: return
        if (args.elementType != E.ARGS || node.elementType == E.NAMED || node.elementType == T.COMMA) return
        val call = args.treeParent ?: return
        if (call.elementType != E.FUNC_CALL) return
        val calleeName = calleeName(call) ?: return
        val metadata = TypstBuiltins.metadata(calleeName) ?: return
        val parameter = metadata.parameters.getOrNull(argumentIndex(args, node)) ?: return
        sink.addPresentation(
            InlineInlayPosition(node.startOffset, true, 0),
            emptyList<InlayPayload>(),
            "Typst parameter",
            false,
        ) {
            text("${parameter.name}:", null)
        }
    }

    private fun argumentIndex(args: com.intellij.lang.ASTNode, argument: com.intellij.lang.ASTNode): Int {
        var index = 0
        var child = args.firstChildNode
        while (child != null && child !== argument) {
            if (child.elementType == T.COMMA) index++
            child = child.treeNext
        }
        return index
    }

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
