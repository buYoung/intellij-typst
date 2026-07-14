package com.livteam.typninja.language.highlighting

import com.intellij.lang.ASTNode
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.analysis.TypstDefinitionKind
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.psi.TypstFieldAccess
import com.livteam.typninja.language.psi.TypstMathIdentifier
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.psi.TypstTokenTypes
import com.livteam.typninja.language.references.TypstReferenceResolver
import com.livteam.typninja.settings.TypstSettingsService

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
class TypstAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val node = element.node ?: return
        // Contextual modifiers span the whole toggle node.
        keyFor(node.elementType)?.let { key ->
            apply(holder, element.textRange, key)
        }
        val settings = TypstSettingsService.getInstance(element.project).state
        if (settings.syntaxOnlyMode || !settings.enableSemanticHighlighting) return
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
                TypstElementTypes.HEADING -> headingHighlight(node)
                TypstElementTypes.FUNC_CALL -> calleeHighlight(node)
                TypstElementTypes.LET_BINDING -> letNameHighlight(node)
                TypstElementTypes.PARAMS -> paramHighlights(node)
                TypstElementTypes.CLOSURE -> closureParamHighlight(node)
                TypstElementTypes.NAMED -> namedArgumentHighlight(node)
                TypstElementTypes.BINDING_DECLARATION -> bindingDeclarationHighlight(node)
                TypstElementTypes.REFERENCE_EXPR -> referenceUsageHighlight(node)
                TypstElementTypes.FIELD_ACCESS -> fieldAccessHighlight(node)
                TypstElementTypes.MATH_REFERENCE -> mathReferenceHighlight(node)
                else -> emptyList()
            }

        private fun fieldAccessHighlight(fieldAccess: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            val field = fieldAccess.psi as? TypstFieldAccess ?: return emptyList()
            val member = lastDirectIdentifier(fieldAccess) ?: return emptyList()
            val parent = fieldAccess.treeParent
            if (parent?.elementType == TypstElementTypes.FUNC_CALL && firstMeaningfulChild(parent) === fieldAccess) {
                // The enclosing call owns its callee color. Highlighting here too would create
                // competing annotations for the same method name.
                return emptyList()
            }
            val metadata = TypstAnalysis.fieldMetadata(field)
            val definition = TypstAnalysis.resolveField(field)
            val key = when {
                metadata?.kind == com.livteam.typninja.language.references.TypstBuiltins.Kind.FUNCTION ->
                    TypstTextAttributeKeys.BUILTIN_FUNCTION
                definition != null -> keyForDefinitionKind(definition.effectiveKind) ?: TypstTextAttributeKeys.FIELD
                else -> TypstTextAttributeKeys.FIELD
            }
            return listOf(member.textRange to key)
        }

        private fun mathReferenceHighlight(mathReference: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            val identifier = mathReference.psi as? TypstMathIdentifier ?: return emptyList()
            val definition = TypstAnalysis.resolveMath(identifier)?.definition ?: return emptyList()
            val name = firstDirectChildOfType(mathReference, TypstTokenTypes.MATH_IDENT) ?: return emptyList()
            val key = when (definition.effectiveKind) {
                TypstDefinitionKind.BUILTIN_FUNCTION, TypstDefinitionKind.LET_FUNCTION -> TypstTextAttributeKeys.FUNCTION_CALL
                TypstDefinitionKind.LET_VARIABLE -> TypstTextAttributeKeys.VARIABLE
                TypstDefinitionKind.PARAMETER, TypstDefinitionKind.LOOP_BINDING -> TypstTextAttributeKeys.PARAMETER
                else -> TypstTextAttributeKeys.MATH_IDENT
            }
            return listOf(name.textRange to key)
        }

        /**
         * A whole heading line ([TypstElementTypes.HEADING]) — marker AND title text. The lexer only
         * colors the leading `=`/`==` marker; the title itself lexes as plain markup TEXT and would stay
         * uncolored. Mirroring tinymist's `markup.heading` scope (which spans the entire heading), this
         * paints the full node with [TypstTextAttributeKeys.HEADING] so headings read as headings. Only
         * the prose spans are colored: an embedded `#code` / `$math$` / `*strong*` inside a heading keeps
         * its own token/semantic colors instead of being flattened to the heading color.
         */
        private fun headingHighlight(heading: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            val out = ArrayList<Pair<TextRange, TextAttributesKey>>()
            var child = heading.firstChildNode
            while (child != null) {
                if (isHeadingProse(child.elementType)) {
                    out.add(child.textRange to TypstTextAttributeKeys.HEADING)
                }
                child = child.treeNext
            }
            return out
        }

        /** Marker + prose leaves/runs of a heading that carry the heading color (not embedded code/math). */
        private fun isHeadingProse(type: IElementType): Boolean = when (type) {
            TypstTokenTypes.HEADING_MARKER,
            TypstTokenTypes.TEXT,
            TypstTokenTypes.SHORTHAND,
            TypstTokenTypes.SMART_QUOTE,
            TypstTokenTypes.ESCAPE,
            TypstElementTypes.MARKUP -> true

            else -> false
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
                // A bare-identifier callee (`#table(…)`) is now wrapped in a REFERENCE_EXPR usage node.
                TypstElementTypes.REFERENCE_EXPR -> firstDirectChildOfType(callee, TypstTokenTypes.IDENTIFIER)
                TypstElementTypes.FIELD_ACCESS -> lastDirectIdentifier(callee)
                else -> null
            } ?: return emptyList()
            // A call's callee always uses the function-call key (a called builtin is still a call). The
            // builtin-specific colors apply to builtins used as VALUES (see referenceUsageHighlight).
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
            collectBindingHighlights(params, TypstTextAttributeKeys.PARAMETER, out)
            return out
        }

        /**
         * A single-parameter closure written without parentheses (`it => …`): the leading identifier
         * is the parameter. Parenthesized closures carry a [TypstElementTypes.PARAMS] child instead,
         * which [paramHighlights] handles, so this only fires for the bare-identifier form.
         */
        private fun closureParamHighlight(closure: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            val first = firstMeaningfulChild(closure) ?: return emptyList()
            return if (first.elementType == TypstElementTypes.BINDING_DECLARATION) {
                firstDirectChildOfType(first, TypstTokenTypes.IDENTIFIER)
                    ?.let { listOf(it.textRange to TypstTextAttributeKeys.PARAMETER) }
                    .orEmpty()
            } else {
                emptyList()
            }
        }

        private fun bindingDeclarationHighlight(binding: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            val identifier = firstDirectChildOfType(binding, TypstTokenTypes.IDENTIFIER) ?: return emptyList()
            var parent = binding.treeParent
            while (parent != null) {
                when (parent.elementType) {
                    TypstElementTypes.PARAMS,
                    TypstElementTypes.CLOSURE,
                    TypstElementTypes.FOR_LOOP ->
                        return listOf(identifier.textRange to TypstTextAttributeKeys.PARAMETER)
                    TypstElementTypes.LET_BINDING ->
                        return listOf(identifier.textRange to TypstTextAttributeKeys.VARIABLE_DEFINITION)
                }
                parent = parent.treeParent
            }
            return emptyList()
        }

        private fun collectBindingHighlights(
            node: ASTNode,
            key: TextAttributesKey,
            output: MutableList<Pair<TextRange, TextAttributesKey>>,
        ) {
            var child = node.firstChildNode
            while (child != null) {
                if (child.elementType == TypstElementTypes.BINDING_DECLARATION) {
                    firstDirectChildOfType(child, TypstTokenTypes.IDENTIFIER)?.let { output.add(it.textRange to key) }
                } else if (
                    child.elementType == TypstElementTypes.NAMED ||
                    child.elementType == TypstElementTypes.DESTRUCTURING ||
                    child.elementType == TypstElementTypes.SPREAD
                ) {
                    collectBindingHighlights(child, key, output)
                }
                child = child.treeNext
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

        /**
         * A code-context identifier USAGE ([TypstElementTypes.REFERENCE_EXPR]) — a `data` in
         * `data.prefix`, an `x` in an expression, the base `obj` of `obj.field`. It is colored by the
         * kind of the definition its file-local reference (F1) resolves to, so reads share a color with
         * their declaration site:
         *  - resolves to a plain `#let v = …` → [TypstTextAttributeKeys.VARIABLE] (variable read),
         *  - resolves to a `#let f(…) = …`   → [TypstTextAttributeKeys.FUNCTION_CALL] (function read),
         *  - resolves to a parameter / closure-param / `#for` binding → [TypstTextAttributeKeys.PARAMETER],
         *  - resolves to nothing (built-in, cross-file `#import`, undefined) → no color, NOT an error.
         *
         * A usage in CALLEE position (`f` in `#f(…)`) is skipped here: its color is owned by
         * [calleeHighlight], which always paints the call site with the function-call key regardless of
         * what the callee resolves to — so a syntactic call never flickers to a variable color.
         */
        private fun referenceUsageHighlight(reference: ASTNode): List<Pair<TextRange, TextAttributesKey>> {
            val parent = reference.treeParent
            if (parent?.elementType == TypstElementTypes.FUNC_CALL && firstMeaningfulChild(parent) === reference) {
                return emptyList()
            }
            val referenceExpression = reference.psi as? TypstReferenceExpression ?: return emptyList()
            val result = TypstReferenceResolver.resolveResult(referenceExpression) ?: return emptyList()
            val key = keyForDefinitionKind(result.definition.effectiveKind) ?: return emptyList()
            val nameNode = firstDirectChildOfType(reference, TypstTokenTypes.IDENTIFIER) ?: reference
            return listOf(nameNode.textRange to key)
        }

        private fun keyForDefinitionKind(kind: TypstDefinitionKind): TextAttributesKey? = when (kind) {
            TypstDefinitionKind.LET_VARIABLE -> TypstTextAttributeKeys.VARIABLE
            TypstDefinitionKind.LET_FUNCTION -> TypstTextAttributeKeys.FUNCTION_CALL
            TypstDefinitionKind.PARAMETER,
            TypstDefinitionKind.LOOP_BINDING -> TypstTextAttributeKeys.PARAMETER
            TypstDefinitionKind.BUILTIN_FUNCTION -> TypstTextAttributeKeys.BUILTIN_FUNCTION
            TypstDefinitionKind.BUILTIN_TYPE -> TypstTextAttributeKeys.BUILTIN_TYPE
            TypstDefinitionKind.BUILTIN_MODULE -> TypstTextAttributeKeys.BUILTIN_FUNCTION
            TypstDefinitionKind.BUILTIN_VALUE -> TypstTextAttributeKeys.VARIABLE
            TypstDefinitionKind.IMPORTED_SYMBOL -> TypstTextAttributeKeys.VARIABLE
            TypstDefinitionKind.MODULE_ALIAS -> TypstTextAttributeKeys.VARIABLE
            TypstDefinitionKind.LABEL -> null
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
