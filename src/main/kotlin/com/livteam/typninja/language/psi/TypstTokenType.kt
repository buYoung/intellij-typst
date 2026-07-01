package com.livteam.typninja.language.psi

import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.TypstLanguage
import org.jetbrains.annotations.NonNls

/**
 * IElementType wrapper for every Typst lexer token.
 *
 * Each instance is bound to [TypstLanguage] so the platform can attribute the token to the
 * Typst language. Instances are created once in [TypstTokenTypes] and are referenced by the
 * hand-written lexer (`TypstLexer`) and by downstream phases (parser, highlighter, commenter).
 *
 * The [toString] override keeps debug output (e.g. PSI viewer, lexer dumps) readable.
 */
class TypstTokenType(@NonNls debugName: String) : IElementType(debugName, TypstLanguage) {
    override fun toString(): String = "TypstTokenType." + super.toString()
}
