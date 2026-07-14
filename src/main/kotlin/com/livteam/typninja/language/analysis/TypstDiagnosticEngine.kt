package com.livteam.typninja.language.analysis

import com.intellij.lang.ASTNode
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

enum class TypstDiagnosticCategory { SEMANTIC, LINT }

data class TypstNativeDiagnostic(
    val range: TextRange,
    val severity: HighlightSeverity,
    val message: String,
    val category: TypstDiagnosticCategory,
)

/** Cached, conservative diagnostics that do not guess dynamic Typst evaluation results. */
object TypstDiagnosticEngine {
    fun diagnosticsFor(element: PsiElement): List<TypstNativeDiagnostic> {
        val file = element.containingFile as? TypstFile ?: return emptyList()
        val snapshot = CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(compute(file), file)
        }
        return snapshot.filter { it.range == element.textRange }
    }

    private fun compute(file: TypstFile): List<TypstNativeDiagnostic> {
        val diagnostics = ArrayList<TypstNativeDiagnostic>()
        collect(file.node, diagnostics)
        return diagnostics
    }

    private fun collect(node: ASTNode, diagnostics: MutableList<TypstNativeDiagnostic>) {
        ProgressManager.checkCanceled()
        when (node.elementType) {
            E.LOOP_BREAK -> if (!hasAncestor(node, E.FOR_LOOP, E.WHILE_LOOP)) {
                diagnostics.add(lint(node, "`break` is only valid inside a loop"))
            }
            E.LOOP_CONTINUE -> if (!hasAncestor(node, E.FOR_LOOP, E.WHILE_LOOP)) {
                diagnostics.add(lint(node, "`continue` is only valid inside a loop"))
            }
            E.FUNC_RETURN -> if (!hasFunctionAncestor(node)) {
                diagnostics.add(lint(node, "`return` is only valid inside a function"))
            }
            E.PARAMS -> duplicateParameters(node, diagnostics)
            E.FUNC_CALL -> callDiagnostics(node, diagnostics)
        }
        var child = node.firstChildNode
        while (child != null) {
            collect(child, diagnostics)
            child = child.treeNext
        }
    }

    private fun duplicateParameters(params: ASTNode, diagnostics: MutableList<TypstNativeDiagnostic>) {
        val names = HashSet<String>()
        var child = params.firstChildNode
        while (child != null) {
            ProgressManager.checkCanceled()
            if (child.elementType == T.IDENTIFIER && !names.add(child.text)) {
                diagnostics.add(semantic(child, "Duplicate parameter `${child.text}`"))
            }
            child = child.treeNext
        }
    }

    private fun callDiagnostics(call: ASTNode, diagnostics: MutableList<TypstNativeDiagnostic>) {
        val signature = TypstSemanticModel.callableForCall(call) ?: return
        val args = call.findChildByType(E.ARGS) ?: return
        val named = HashSet<String>()
        var positionalCount = 0
        var child = args.firstChildNode
        while (child != null) {
            ProgressManager.checkCanceled()
            when (child.elementType) {
                E.NAMED -> {
                    val name = child.findChildByType(T.IDENTIFIER)?.text ?: ""
                    if (name.isNotEmpty() && !named.add(name)) diagnostics.add(semantic(child, "Duplicate named argument `$name`"))
                    if (name.isNotEmpty() && signature.isParameterListComplete && signature.parameters.none { it.name == name }) {
                        diagnostics.add(semantic(child, "Unknown named argument `$name`"))
                    }
                }
                T.LPAREN, T.RPAREN, T.COMMA, com.intellij.psi.TokenType.WHITE_SPACE, T.LINE_COMMENT, T.BLOCK_COMMENT, T.PARBREAK -> Unit
                else -> positionalCount++
            }
            child = child.treeNext
        }
        signature.parameters.filter { it.isRequired }.forEachIndexed { index, parameter ->
            if (parameter.name !in named && index >= positionalCount) {
                diagnostics.add(semantic(call, "Required argument `${parameter.name}` is missing"))
            }
        }
    }

    private fun hasFunctionAncestor(node: ASTNode): Boolean {
        var current = node.treeParent
        while (current != null) {
            if (current.elementType == E.LET_BINDING && current.findChildByType(E.PARAMS) != null) return true
            current = current.treeParent
        }
        return false
    }

    private fun hasAncestor(node: ASTNode, vararg types: com.intellij.psi.tree.IElementType): Boolean {
        var current = node.treeParent
        while (current != null) {
            if (current.elementType in types) return true
            current = current.treeParent
        }
        return false
    }

    private fun semantic(node: ASTNode, message: String) =
        TypstNativeDiagnostic(node.textRange, HighlightSeverity.WEAK_WARNING, message, TypstDiagnosticCategory.SEMANTIC)

    private fun lint(node: ASTNode, message: String) =
        TypstNativeDiagnostic(node.textRange, HighlightSeverity.WARNING, message, TypstDiagnosticCategory.LINT)
}
