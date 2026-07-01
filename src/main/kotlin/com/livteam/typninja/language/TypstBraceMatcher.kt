package com.livteam.typninja.language

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * Highlights matching `()`, `{}` and `[]` delimiters in Typst source and drives brace
 * auto-insertion / navigation.
 *
 * The delimiter tokens ([TypstTokenTypes.LPAREN] etc.) are emitted by the lexer regardless of
 * markup / code / math context, so pairing is purely token-based and needs no PSI lookup. `{}` is
 * marked structural because a Typst `{ ... }` code block is the closest analogue to a structural
 * block; `()` and `[]` are non-structural (arguments / arrays / content).
 *
 * Stateless; registered via `<lang.braceMatcher language="Typst" .../>`.
 */
class TypstBraceMatcher : PairedBraceMatcher {

    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        private val PAIRS: Array<BracePair> = arrayOf(
            BracePair(TypstTokenTypes.LPAREN, TypstTokenTypes.RPAREN, false),
            BracePair(TypstTokenTypes.LBRACE, TypstTokenTypes.RBRACE, true),
            BracePair(TypstTokenTypes.LBRACKET, TypstTokenTypes.RBRACKET, false),
        )
    }
}
