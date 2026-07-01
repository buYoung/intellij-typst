package com.livteam.typninja.language.highlighting

import com.intellij.lang.ASTNode
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * PSI-driven highlighting annotator for Typst (FDD 9.2 contextual modifiers + semantic colors).
 *
 * The lexer-based [TypstSyntaxHighlighter] cannot express two kinds of highlighting because it only
 * sees single tokens without grammar context:
 *  1. **Contextual modifiers** — "this whole span is bold/italic". Applied to the toggle nodes:
 *     `*strong*` ([TypstElementTypes.STRONG]) and `_emph_` ([TypstElementTypes.EMPH]).
 *  2. **Semantic colors** — every identifier is lexed as the same `IDENTIFIER` token, so `#table(…)`
 *     (a call) and `data` (a variable) get the same color. Only the parsed tree can tell them apart.
 *     This annotator colors the callee of a call, the name a `let` binds, closure/function parameter
 *     names, and named-argument keys with dedicated keys (each with a platform fallback).
 *
 * It is deliberately file-local and stateless: it only inspects the node passed in plus its immediate
 * children (and, for a named argument, its single parent), never a project index, reference resolution
 * or a deep PSI walk, so it is fast enough to run on every keystroke and fails soft — if an attribute
 * cannot be applied the base lexer colors remain. Registered via `<annotator language="Typst" .../>`.
 */
class TypstAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val node = element.node ?: return
        // Contextual modifiers span the whole toggle node.
        keyFor(node.elementType)?.let { key ->
            apply(holder, element.textRange, key)
        }
        // Semantic colors target a specific identifier range inside the node.
        for ((range, key) in semanticHighlights(node)) {
            apply(holder, range, key)
        }
    }

    private fun apply(holder: AnnotationHolder, range: TextRange, key: TextAttributesKey) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .textAttributes(key)
            .create()
    }

    companion object {
        /**
         * Maps a PSI element type to its contextual-modifier attribute key, or `null` when the node is
         * not a markup emphasis toggle. Exposed for deterministic unit testing of the mapping.
         */
        fun keyFor(elementType: IElementType?): TextAttributesKey? = when (elementType) {
            TypstElementTypes.STRONG -> TypstTextAttributeKeys.STRONG
            TypstElementTypes.EMPH -> TypstTextAttributeKeys.EMPH
            else -> null
        }

        /**
         * Pure mapping from a parsed node to the semantic (range, key) highlights it contributes.
         * Returns an empty list for every node that is not a call / binding / parameter / named
         * argument. Kept side-effect-free so it can be asserted directly in tests against real PSI.
         */
        fun semanticHighlights(node: ASTNode): List<Pair<TextRange, TextAttributesKey>> =
            when (node.elementType) {
                TypstElementTypes.FUNC_CALL -> calleeHighlight(node)
                TypstElementTypes.LET_BINDING -> letNameHighlight(node)
                TypstElementTypes.PARAMS -> paramHighlights(node)
                TypstElementTypes.CLOSURE -> closureParamHighlight(node)
                TypstElementTypes.NAMED -> namedArgumentHighlight(node)
                else -> emptyList()
            }

        /**
         * The callee of a call: a bare identifier (`#table(…)`) is the call name; a field access used
         * as a call (`#obj.method(…)`) highlights only the method name (the last identifier after the
         * last dot). A callee that is neither (a nested call, a parenthesized expression) contributes
         * nothing, so chained `#f(a)(b)` colors only the innermost `f`.
         */
        private fun calleeHighlight(funcCall: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            val callee = firstMeaningfulChild(funcCall) ?: return emptyList()
            val nameNode = when (callee.elementType) {
                TypstTokenTypes.IDENTIFIER -> callee
                TypstElementTypes.FIELD_ACCESS -> lastDirectIdentifier(callee)
                else -> null
            } ?: return emptyList()
            return listOf(nameNode.textRange to TypstTextAttributeKeys.FUNCTION_CALL)
        }

        /**
         * The name a `let` binds: the first identifier after `let`. When a `(params)` list directly
         * follows the name it is a function definition (`#let f(x) = …`) so the name gets the
         * declaration key; otherwise it is a value binding (`#let v = …`) and gets the variable key.
         * Destructuring / `_` bindings own no plain identifier here and contribute nothing.
         */
        private fun letNameHighlight(letBinding: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            val name = firstDirectChildOfType(letBinding, TypstTokenTypes.IDENTIFIER) ?: return emptyList()
            val isFunction = nextMeaningfulSibling(name)?.elementType == TypstElementTypes.PARAMS
            val key = if (isFunction) TypstTextAttributeKeys.FUNCTION_DECLARATION
            else TypstTextAttributeKeys.VARIABLE_DEFINITION
            return listOf(name.textRange to key)
        }

        /**
         * Parameter names in a `(params)` list (both closures and `#let f(params)` definitions):
         * every plain identifier parameter, plus the key of a `name: default` ([TypstElementTypes.NAMED])
         * parameter. Spread / destructuring / `_` parameters contribute nothing.
         */
        private fun paramHighlights(params: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            val out = ArrayList<Pair<TextRange, TextAttributesKey>>()
            var child = params.firstChildNode
            while (child != null) {
                when (child.elementType) {
                    TypstTokenTypes.IDENTIFIER ->
                        out.add(child.textRange to TypstTextAttributeKeys.PARAMETER)
                    TypstElementTypes.NAMED ->
                        firstDirectChildOfType(child, TypstTokenTypes.IDENTIFIER)?.let {
                            out.add(it.textRange to TypstTextAttributeKeys.PARAMETER)
                        }
                }
                child = child.treeNext
            }
            return out
        }

        /**
         * A single-parameter closure written without parentheses (`it => …`): the leading identifier
         * is the parameter. Parenthesized closures carry a [TypstElementTypes.PARAMS] child instead,
         * which [paramHighlights] handles, so this only fires for the bare-identifier form.
         */
        private fun closureParamHighlight(closure: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            val first = firstMeaningfulChild(closure) ?: return emptyList()
            return if (first.elementType == TypstTokenTypes.IDENTIFIER) {
                listOf(first.textRange to TypstTextAttributeKeys.PARAMETER)
            } else {
                emptyList()
            }
        }

        /**
         * The key of a named argument, e.g. `size` in `text(size: 12pt)`. Gated on the parent being an
         * [TypstElementTypes.ARGS] list so a `NAMED` used as a dict entry or a named parameter default
         * is not colored as a call argument (those are handled by their own owners).
         */
        private fun namedArgumentHighlight(named: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            if (named.treeParent?.elementType != TypstElementTypes.ARGS) return emptyList()
            val key = firstDirectChildOfType(named, TypstTokenTypes.IDENTIFIER) ?: return emptyList()
            return listOf(key.textRange to TypstTextAttributeKeys.NAMED_ARGUMENT)
        }

        // ---- ASTNode navigation helpers (direct children only; whitespace/comments skipped) ----

        private fun firstMeaningfulChild(node: ASTNode): ASTNode? {
            var child = node.firstChildNode
            while (child != null && isSkippable(child)) child = child.treeNext
            return child
        }

        private fun nextMeaningfulSibling(node: ASTNode): ASTNode? {
            var sibling = node.treeNext
            while (sibling != null && isSkippable(sibling)) sibling = sibling.treeNext
            return sibling
        }

        private fun firstDirectChildOfType(node: ASTNode, type: IElementType): ASTNode? {
            var child = node.firstChildNode
            while (child != null) {
                if (child.elementType == type) return child
                child = child.treeNext
            }
            return null
        }

        private fun lastDirectIdentifier(node: ASTNode): ASTNode? {
            var child = node.firstChildNode
            var last: ASTNode? = null
            while (child != null) {
                if (child.elementType == TypstTokenTypes.IDENTIFIER) last = child
                child = child.treeNext
            }
            return last
        }

        private fun isSkippable(node: ASTNode): Boolean = when (node.elementType) {
            TokenType.WHITE_SPACE,
            TypstTokenTypes.LINE_COMMENT,
            TypstTokenTypes.BLOCK_COMMENT,
            TypstTokenTypes.PARBREAK -> true

            else -> false
        }
    }
}
