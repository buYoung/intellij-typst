package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.livteam.typninja.language.TypstLanguage

/**
 * Node type for the Typst PSI file root (returned by `getFileNodeType()`).
 *
 * Kept separate from [TypstElementTypes] so it can be referenced as a stable, single file-node
 * type by the parser definition and by stub/index infrastructure later.
 */
object TypstFileElementType : IFileElementType("Typst.FILE", TypstLanguage)

/**
 * Composite (non-leaf) element types for the Typst PSI tree (Typst 0.15.0 grammar).
 *
 * Names track `typst-syntax`'s `SyntaxKind` roughly 1:1 for review/maintenance convenience. Downstream
 * phases dispatch on `ASTNode.getElementType()` against these values, so the set is ADDITIVE: the
 * region names earlier phases already consumed ([MARKUP], [MATH], [CODE_BLOCK], [CONTENT_BLOCK],
 * [RAW], [STRING_LITERAL], [CODE_EXPRESSION]) are kept verbatim. The finer P3 grammar emits
 * [ARRAY]/[DICT]/[ARGS]/… and [REF]; the legacy region names `PAREN_GROUP` and `LABEL` those replaced
 * were removed in P4 once no downstream code referenced them.
 *
 * The tree is a real recursive-descent + precedence-climbing AST, not a region grouper:
 *  - markup:  [HEADING] [LIST_ITEM] [ENUM_ITEM] [TERM_ITEM] [STRONG] [EMPH] [REF] [MARKUP] prose runs,
 *             [CONTENT_BLOCK], [RAW], [MATH] equations.
 *  - code:    statements ([LET_BINDING] [SET_RULE] [SHOW_RULE] [CONTEXTUAL] [CONDITIONAL] [WHILE_LOOP]
 *             [FOR_LOOP] [MODULE_IMPORT] [MODULE_INCLUDE] [FUNC_RETURN] [LOOP_BREAK] [LOOP_CONTINUE]),
 *             expressions ([BINARY] [UNARY] [FUNC_CALL] [FIELD_ACCESS] [CLOSURE]),
 *             collections ([ARRAY] [DICT] [PARENTHESIZED] [ARGS] [PARAMS] [DESTRUCTURING]) and their
 *             entries ([NAMED] [KEYED] [SPREAD]), plus [DESTRUCT_ASSIGNMENT] and [IMPORT_ITEMS].
 *  - math:    shallow but structured — [MATH] wraps an equation, [MATH_DELIMITED] a nested group.
 */
object TypstElementTypes {

    // ---- kept region names (stable downstream contract) ----
    @JvmField val MARKUP: IElementType = TypstElementType("MARKUP")
    @JvmField val CODE_EXPRESSION: IElementType = TypstElementType("CODE_EXPRESSION")
    @JvmField val MATH: IElementType = TypstElementType("MATH")
    @JvmField val RAW: IElementType = TypstElementType("RAW")
    @JvmField val STRING_LITERAL: IElementType = TypstElementType("STRING_LITERAL")
    @JvmField val CONTENT_BLOCK: IElementType = TypstElementType("CONTENT_BLOCK")
    @JvmField val CODE_BLOCK: IElementType = TypstElementType("CODE_BLOCK")

    // ---- markup nodes ----
    @JvmField val HEADING: IElementType = TypstElementType("HEADING")
    @JvmField val LIST_ITEM: IElementType = TypstElementType("LIST_ITEM")
    @JvmField val ENUM_ITEM: IElementType = TypstElementType("ENUM_ITEM")
    @JvmField val TERM_ITEM: IElementType = TypstElementType("TERM_ITEM")
    @JvmField val STRONG: IElementType = TypstElementType("STRONG")
    @JvmField val EMPH: IElementType = TypstElementType("EMPH")
    @JvmField val REF: IElementType = TypstElementType("REF")
    @JvmField val LABEL: IElementType = TypstElementType("LABEL")

    // ---- code statements ----
    @JvmField val LET_BINDING: IElementType = TypstElementType("LET_BINDING")
    @JvmField val SET_RULE: IElementType = TypstElementType("SET_RULE")
    @JvmField val SHOW_RULE: IElementType = TypstElementType("SHOW_RULE")
    @JvmField val CONTEXTUAL: IElementType = TypstElementType("CONTEXTUAL")
    @JvmField val CONDITIONAL: IElementType = TypstElementType("CONDITIONAL")
    @JvmField val WHILE_LOOP: IElementType = TypstElementType("WHILE_LOOP")
    @JvmField val FOR_LOOP: IElementType = TypstElementType("FOR_LOOP")
    @JvmField val MODULE_IMPORT: IElementType = TypstElementType("MODULE_IMPORT")
    @JvmField val IMPORT_ITEMS: IElementType = TypstElementType("IMPORT_ITEMS")
    @JvmField val IMPORT_ITEM: IElementType = TypstElementType("IMPORT_ITEM")
    /** Explicit `*` in an import item list. It is not an import item or a declaration. */
    @JvmField val IMPORT_GLOB: IElementType = TypstElementType("IMPORT_GLOB")
    @JvmField val MODULE_INCLUDE: IElementType = TypstElementType("MODULE_INCLUDE")
    @JvmField val LOOP_BREAK: IElementType = TypstElementType("LOOP_BREAK")
    @JvmField val LOOP_CONTINUE: IElementType = TypstElementType("LOOP_CONTINUE")
    @JvmField val FUNC_RETURN: IElementType = TypstElementType("FUNC_RETURN")

    // ---- code expressions ----
    /**
     * A code-context identifier USAGE (a variable/function reference). The parser wraps every plain
     * identifier read in code context in this node so it can carry a [com.intellij.psi.PsiReference]
     * (see [TypstReferenceExpression]); definition names, field members and named-argument keys are
     * left as bare [TypstTokenTypes.IDENTIFIER] leaves and carry no reference.
     */
    @JvmField val REFERENCE_EXPR: IElementType = TypstElementType("REFERENCE_EXPR")
    @JvmField val FUNC_CALL: IElementType = TypstElementType("FUNC_CALL")
    @JvmField val ARGS: IElementType = TypstElementType("ARGS")
    @JvmField val FIELD_ACCESS: IElementType = TypstElementType("FIELD_ACCESS")
    @JvmField val CLOSURE: IElementType = TypstElementType("CLOSURE")
    @JvmField val PARAMS: IElementType = TypstElementType("PARAMS")
    @JvmField val UNARY: IElementType = TypstElementType("UNARY")
    @JvmField val BINARY: IElementType = TypstElementType("BINARY")

    // ---- collections & entries ----
    @JvmField val ARRAY: IElementType = TypstElementType("ARRAY")
    @JvmField val DICT: IElementType = TypstElementType("DICT")
    @JvmField val PARENTHESIZED: IElementType = TypstElementType("PARENTHESIZED")
    @JvmField val NAMED: IElementType = TypstElementType("NAMED")
    @JvmField val KEYED: IElementType = TypstElementType("KEYED")
    @JvmField val SPREAD: IElementType = TypstElementType("SPREAD")
    @JvmField val DESTRUCTURING: IElementType = TypstElementType("DESTRUCTURING")
    @JvmField val DESTRUCT_ASSIGNMENT: IElementType = TypstElementType("DESTRUCT_ASSIGNMENT")

    // ---- math nodes (shallow) ----
    @JvmField val MATH_DELIMITED: IElementType = TypstElementType("MATH_DELIMITED")

    /**
     * Factory used by the parser definition's `createElement`. Most Typst composite nodes map to the
     * generic [TypstPsiElement] (the element type carries the region identity), but the nodes that
     * participate in reference resolution get dedicated PSI classes: [LET_BINDING] is a
     * [com.intellij.psi.PsiNameIdentifierOwner] declaration ([TypstLetBinding]) and [REFERENCE_EXPR]
     * carries the usage's [com.intellij.psi.PsiReference] ([TypstReferenceExpression]).
     */
    fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        MARKUP -> TypstMarkup(node)
        LET_BINDING -> TypstLetBinding(node)
        REFERENCE_EXPR -> TypstReferenceExpression(node)
        REF -> TypstRef(node)
        LABEL -> TypstLabelDefinition(node)
        MODULE_IMPORT -> TypstModuleImport(node)
        IMPORT_ITEM -> TypstImportItem(node)
        FIELD_ACCESS -> TypstFieldAccess(node)
        NAMED -> TypstNamedArgument(node)
        STRING_LITERAL -> TypstStringLiteral(node)
        else -> TypstPsiElement(node)
    }
}
