package com.livteam.typninja.language.lexer

import com.intellij.lexer.FlexAdapter

/**
 * IntelliJ [com.intellij.lexer.Lexer] for Typst, wrapping the generated JFlex lexer.
 *
 * `_TypstLexer` is generated from `src/main/grammar/Typst.flex` into
 * `com.livteam.typninja.language.lexer` by the `generateLexer` Gradle task.
 *
 * Lexers are stateful and single-use per lex run; create a fresh `TypstLexerAdapter()` at every
 * call site (parser definition, syntax highlighter, etc.). The instance holds no shared global
 * state, and the `null` reader is replaced by [FlexAdapter] on each `start(...)` call.
 *
 * The generated lexer keeps a nesting frame stack and block-comment depth as fields (see the
 * `Typst.flex` header). Those fields are NOT part of the JFlex lexical-state int, so they are cleared
 * on every [start] before delegating: a full forward pass always begins from an empty stack and is
 * therefore correct. Incremental relexing that restarts mid-document from a stored lexical state is
 * an approximate (documented over-relex) case and self-corrects on the next full pass.
 */
class TypstLexerAdapter : FlexAdapter(_TypstLexer(null)) {

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        (flex as _TypstLexer).resetState()
        super.start(buffer, startOffset, endOffset, initialState)
    }
}
