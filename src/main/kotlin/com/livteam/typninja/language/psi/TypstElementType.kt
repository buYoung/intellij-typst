package com.livteam.typninja.language.psi

import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.TypstLanguage
import org.jetbrains.annotations.NonNls

/**
 * [IElementType] wrapper for Typst COMPOSITE (non-leaf) nodes produced by the parser.
 *
 * This is deliberately distinct from [TypstTokenType] (leaf/token types emitted by the lexer):
 * keeping token types and composite element types in separate classes/holders avoids the
 * Grammar-Kit "two mismatched token sets" hazard and makes the PSI contract explicit.
 *
 * Instances are created once in [TypstElementTypes].
 */
class TypstElementType(@NonNls debugName: String) : IElementType(debugName, TypstLanguage) {
    override fun toString(): String = "TypstElementType." + super.toString()
}
