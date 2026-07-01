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
 * Composite (non-leaf) element types for the Typst MVP PSI tree.
 *
 * These constants are the STABLE CONTRACT downstream phases dispatch on (phase 06 highlighting,
 * phase 07 formatter/commenter): later phases switch on `ASTNode.getElementType()` against these
 * values rather than reaching into parser internals. They model, at conservative depth, the syntax
 * regions required by the brief:
 *
 *   - [MARKUP]          a run of markup prose (TEXT / ESCAPE leaves) in document context
 *   - [CODE_EXPRESSION] a `#`-introduced code expression
 *   - [MATH]            a `$ ... $` math region
 *   - [RAW]             inline/fenced raw text (wraps a RAW_TEXT leaf)
 *   - [STRING_LITERAL]  a `"..."` string (wraps a STRING leaf)
 *   - [LABEL]           a reference-like `@name` sigil + identifier
 *   - [CONTENT_BLOCK]   a `[ ... ]` content block (block-like structure)
 *   - [CODE_BLOCK]      a `{ ... }` code block (block-like structure)
 *   - [PAREN_GROUP]     a `( ... )` group: args / array / dict (block-like structure)
 *
 * Comments and delimiters are NOT composite nodes: comment leaves are made `PsiComment`
 * automatically via `getCommentTokens()`, and delimiters stay as token leaves inside the groups
 * above. Heading/list/emphasis markers are absorbed into [MARKUP] (the lexer emits them as TEXT);
 * finer structure is deferred per the partial-support policy.
 */
object TypstElementTypes {

    @JvmField val MARKUP: IElementType = TypstElementType("MARKUP")
    @JvmField val CODE_EXPRESSION: IElementType = TypstElementType("CODE_EXPRESSION")
    @JvmField val MATH: IElementType = TypstElementType("MATH")
    @JvmField val RAW: IElementType = TypstElementType("RAW")
    @JvmField val STRING_LITERAL: IElementType = TypstElementType("STRING_LITERAL")
    @JvmField val LABEL: IElementType = TypstElementType("LABEL")
    @JvmField val CONTENT_BLOCK: IElementType = TypstElementType("CONTENT_BLOCK")
    @JvmField val CODE_BLOCK: IElementType = TypstElementType("CODE_BLOCK")
    @JvmField val PAREN_GROUP: IElementType = TypstElementType("PAREN_GROUP")

    /**
     * Factory used by the parser definition's `createElement`. Every Typst composite node maps to a
     * single generic [TypstPsiElement]; the element type (above) carries the region identity. This
     * keeps the MVP narrow while still exposing a typed, queryable PSI tree.
     */
    fun createElement(node: ASTNode): PsiElement = TypstPsiElement(node)
}
