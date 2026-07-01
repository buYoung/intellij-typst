package com.livteam.typninja.language.lexer

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * Golden lexer-recovery tests locking the invariants R3 fixed. Drives [TypstLexer] directly and
 * asserts on the emitted token stream (type + text), so the checks are deterministic and do not
 * depend on any external gold file.
 */
class TypstLexerRecoveryTest : BasePlatformTestCase() {

    private data class Token(val type: IElementType, val text: String)

    private fun lex(input: String): List<Token> {
        val lexer = TypstLexer()
        lexer.start(input, 0, input.length, 0)
        val tokens = ArrayList<Token>()
        while (true) {
            val type = lexer.tokenType ?: break
            tokens.add(Token(type, lexer.tokenText))
            lexer.advance()
        }
        return tokens
    }

    private fun assertFullyCovered(input: String, tokens: List<Token>) {
        assertEquals("token stream must reconstruct the input", input, tokens.joinToString("") { it.text })
    }

    fun testContentBlockProseHasNoBadCharacter() {
        val input = "#emph[Why?]"
        val tokens = lex(input)
        assertFalse(
            "content-block prose must not produce BAD_CHARACTER",
            tokens.any { it.type == TokenType.BAD_CHARACTER },
        )
        // The `?` is prose text inside the content block, not a stray character.
        assertTrue(
            "prose punctuation must be TEXT",
            tokens.any { it.type == TypstTokenTypes.TEXT && it.text.contains("?") },
        )
        assertFullyCovered(input, tokens)
    }

    fun testUnclosedStringIsBoundedToItsLine() {
        val input = "#let s = \"line\ndef\""
        val tokens = lex(input)
        val string = tokens.first { it.type == TypstTokenTypes.STRING }
        assertEquals("unterminated string must stop at the newline", "\"line", string.text)
        assertFalse("string must not cross a newline", string.text.contains("\n"))
    }

    fun testUnterminatedBlockCommentIsSingleTokenToEof() {
        val input = "before /* never ends here"
        val tokens = lex(input)
        val comments = tokens.filter { it.type == TypstTokenTypes.BLOCK_COMMENT }
        assertEquals("unterminated block comment must be exactly one token", 1, comments.size)
        assertEquals("/* never ends here", comments.single().text)
        assertFullyCovered(input, tokens)
    }

    fun testNestedBlockCommentIsSingleToken() {
        val input = "/* outer /* inner */ outer */ text"
        val tokens = lex(input)
        val comments = tokens.filter { it.type == TypstTokenTypes.BLOCK_COMMENT }
        assertEquals("nested block comment must be one token", 1, comments.size)
        assertEquals("/* outer /* inner */ outer */", comments.single().text)
        assertTrue(
            "trailing prose after the comment must remain TEXT",
            tokens.any { it.type == TypstTokenTypes.TEXT && it.text.contains("text") },
        )
    }

    fun testMultilineCodeBlockStaysCodeAcrossNewlines() {
        val input = "#{\n  let x = 1\n}"
        val tokens = lex(input)
        val types = tokens.map { it.type }.toSet()
        assertTrue("`let` keyword must be lexed as code", TypstTokenTypes.KW_LET in types)
        assertTrue("identifier inside the block must be code", TypstTokenTypes.IDENTIFIER in types)
        assertTrue("assignment must be code", TypstTokenTypes.EQ in types)
        assertTrue("integer literal must be code", TypstTokenTypes.INTEGER_LITERAL in types)
        assertTrue("the closing brace must be reached", TypstTokenTypes.RBRACE in types)
        assertFalse("multi-line code must not fall back to BAD_CHARACTER", tokens.any { it.type == TokenType.BAD_CHARACTER })
    }

    fun testDeeplyNestedBracketsTerminate() {
        val depth = 5000
        val input = "[".repeat(depth)
        val tokens = lex(input)
        // The point of the test is simply that lexing completes (no hang / no infinite loop).
        assertEquals(depth, tokens.size)
        assertTrue(tokens.all { it.type == TypstTokenTypes.LBRACKET })
    }
}
