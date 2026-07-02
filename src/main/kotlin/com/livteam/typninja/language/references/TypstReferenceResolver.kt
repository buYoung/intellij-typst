package com.livteam.typninja.language.references

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.psi.TypstReferenceExpression
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

/**
 * File-local (lexical) scope resolution for a code-context identifier usage.
 *
 * Given a [TypstReferenceExpression] usage, [resolve] walks UP the tree from the usage and, at each
 * enclosing scope, looks for a definition that binds the used name. The first (nearest) match wins,
 * so an inner binding shadows an outer one. It returns the definition's NAME element (a navigable
 * identifier leaf), or `null` when nothing file-local binds the name.
 *
 * Scopes and the names they introduce:
 *  - a [E.CLOSURE] — its parameters (a [E.PARAMS] child, or the leading identifier of `it => …`),
 *  - a [E.FOR_LOOP] — its loop-pattern binding(s), visible only in the loop BODY (not the iterable),
 *  - a [E.LET_BINDING] with a [E.PARAMS] child — the function parameters, visible in the body,
 *  - any block-like node (the file root, a [E.CODE_BLOCK], a [E.CONTENT_BLOCK], …) — the `#let`
 *    statements declared directly in it. Within one scope the declaration nearest above the usage
 *    wins (positional shadowing of `#let x = 1; #let x = 2`), falling back to the first declaration
 *    for forward references.
 *
 * OUT OF SCOPE (returns `null` gracefully): cross-file `#import` targets (needs indexing), built-ins,
 * spread sinks (`..args`) and rename-destructuring targets (`(a: x)`). No indexing, no stubs, no
 * cross-file work; safe to call on a background thread and cheap enough for the EDT.
 */
object TypstReferenceResolver {

    fun resolve(usage: TypstReferenceExpression): PsiElement? {
        val name = usage.referenceName
        if (name.isEmpty()) return null
        val usageNode = usage.node
        val usageOffset = usageNode.startOffset

        var scope: ASTNode? = usageNode.treeParent
        while (scope != null) {
            ProgressManager.checkCanceled()
            findInScope(scope, name, usageNode, usageOffset)?.let { return it.psi }
            scope = scope.treeParent
        }
        // Nothing file-local binds the name: try a symbol brought in by a relative `#import`, then a
        // Typst standard-library builtin. Both degrade to `null` (built-in DB miss / package import).
        TypstModuleResolver.resolveImportedName(usage.containingFile, name)?.let { return it }
        return TypstBuiltinResolver.resolve(usage, name)
    }

    /** Names introduced by [scope] itself, then the `#let` declarations directly inside it. */
    private fun findInScope(scope: ASTNode, name: String, usageNode: ASTNode, usageOffset: Int): ASTNode? {
        when (scope.elementType) {
            E.CLOSURE -> matchClosureParam(scope, name)?.let { return it }
            E.FOR_LOOP -> if (isInLoopBody(scope, usageOffset)) matchForBinding(scope, name)?.let { return it }
            E.LET_BINDING -> matchFunctionParams(scope, name)?.let { return it }
        }
        return matchLetDeclaration(scope, name, usageOffset)
    }

    // ---- #let declarations in a block-like scope ----

    /**
     * The name element of a `#let` declared directly in [scope] that binds [name]. At markup level a
     * `#let` is wrapped in a [E.CODE_EXPRESSION]; inside a `{ … }` code block it is a direct child, so
     * both shapes are unwrapped. The declaration nearest above the usage wins.
     */
    private fun matchLetDeclaration(scope: ASTNode, name: String, usageOffset: Int): ASTNode? {
        var best: ASTNode? = null       // nearest declaration at/before the usage
        var fallback: ASTNode? = null   // first matching declaration (forward reference)
        var child = scope.firstChildNode
        while (child != null) {
            val letBinding = when (child.elementType) {
                E.LET_BINDING -> child
                E.CODE_EXPRESSION -> child.findChildByType(E.LET_BINDING)
                else -> null
            }
            if (letBinding != null) {
                val nameLeaf = letName(letBinding)
                if (nameLeaf != null && nameLeaf.text == name) {
                    if (fallback == null) fallback = nameLeaf
                    if (nameLeaf.startOffset <= usageOffset &&
                        (best == null || nameLeaf.startOffset > best!!.startOffset)
                    ) {
                        best = nameLeaf
                    }
                }
            }
            child = child.treeNext
        }
        return best ?: fallback
    }

    /** The bound name of a `#let`: its first direct identifier child (null for destructuring / `_`). */
    private fun letName(letBinding: ASTNode): ASTNode? = firstDirectChildOfType(letBinding, T.IDENTIFIER)

    // ---- parameters ----

    private fun matchFunctionParams(letBinding: ASTNode, name: String): ASTNode? {
        val params = letBinding.findChildByType(E.PARAMS) ?: return null
        return matchParam(params, name)
    }

    private fun matchClosureParam(closure: ASTNode, name: String): ASTNode? {
        val params = closure.findChildByType(E.PARAMS)
        if (params != null) return matchParam(params, name)
        // bare `it => …`: the parameter is the closure's leading identifier.
        val first = firstMeaningfulChild(closure) ?: return null
        return if (first.elementType == T.IDENTIFIER && first.text == name) first else null
    }

    /**
     * A binding identifier equal to [name] inside a parameter list / destructuring pattern: a plain
     * identifier parameter, the key of a `name: default` parameter, or a name nested in a
     * destructuring group. Spread sinks (`..name`) are parsed as expressions and intentionally skipped.
     */
    private fun matchParam(container: ASTNode, name: String): ASTNode? {
        var child = container.firstChildNode
        while (child != null) {
            when (child.elementType) {
                T.IDENTIFIER -> if (child.text == name) return child
                E.NAMED -> {
                    val key = firstDirectChildOfType(child, T.IDENTIFIER)
                    if (key != null && key.text == name) return key
                }
                E.DESTRUCTURING -> matchParam(child, name)?.let { return it }
            }
            child = child.treeNext
        }
        return null
    }

    // ---- for-loop binding ----

    /**
     * The loop-pattern binding equal to [name]: the identifier(s) between `for` and `in`. Scanning
     * stops at `in` so the iterable expression is never treated as a binding.
     */
    private fun matchForBinding(forLoop: ASTNode, name: String): ASTNode? {
        var child = forLoop.firstChildNode
        while (child != null && child.elementType != T.KW_IN) {
            when (child.elementType) {
                T.IDENTIFIER -> if (child.text == name) return child
                E.DESTRUCTURING -> matchParam(child, name)?.let { return it }
            }
            child = child.treeNext
        }
        return null
    }

    /** The loop binding is only visible in the body block (the part after `in`), not the iterable. */
    private fun isInLoopBody(forLoop: ASTNode, usageOffset: Int): Boolean {
        val body = loopBody(forLoop) ?: return false
        return body.textRange.contains(usageOffset)
    }

    private fun loopBody(forLoop: ASTNode): ASTNode? {
        var child = forLoop.lastChildNode
        while (child != null) {
            if (child.elementType == E.CODE_BLOCK || child.elementType == E.CONTENT_BLOCK) return child
            child = child.treePrev
        }
        return null
    }

    // ---- AST navigation helpers (direct children only) ----

    private fun firstDirectChildOfType(node: ASTNode, type: IElementType): ASTNode? {
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType == type) return child
            child = child.treeNext
        }
        return null
    }

    private fun firstMeaningfulChild(node: ASTNode): ASTNode? {
        var child = node.firstChildNode
        while (child != null && isSkippable(child.elementType)) child = child.treeNext
        return child
    }

    private fun isSkippable(type: IElementType): Boolean = when (type) {
        TokenType.WHITE_SPACE, T.LINE_COMMENT, T.BLOCK_COMMENT, T.PARBREAK -> true
        else -> false
    }
}
