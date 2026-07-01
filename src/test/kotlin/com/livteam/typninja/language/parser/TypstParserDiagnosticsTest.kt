package com.livteam.typninja.language.parser

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.livteam.typninja.language.psi.TypstFile

/**
 * Diagnostic-message gate for run `typst-lang-intel-03` F3 (contextual error messages).
 *
 * These assert the ACTUAL [PsiErrorElement.errorDescription] text on invalid input — proving the
 * parser now emits contextual hints ("Expected an expression", "Unmatched ')'", "Expected a binding
 * name", "Unexpected character 'x'") instead of a bare "Unexpected token". The companion cases assert
 * that valid AND incomplete-while-typing input stays at ZERO errors, which is the hard invariant F3
 * must never break (prefer silence over false errors on in-progress code).
 */
class TypstParserDiagnosticsTest : BasePlatformTestCase() {

    private fun descriptionsOf(text: String): List<String> {
        val file = myFixture.configureByText("test.typ", text) as TypstFile
        PsiTreeUtil.processElements(file) { true } // force the whole AST
        return PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
            .map { it.errorDescription }
    }

    private fun assertOnlyError(text: String, expected: String) {
        val descriptions = descriptionsOf(text)
        assertEquals("`$text` must produce exactly one error: $descriptions", 1, descriptions.size)
        assertEquals("`$text` error message", expected, descriptions.single())
    }

    private fun assertZeroErrors(text: String) {
        val descriptions = descriptionsOf(text)
        assertTrue("`$text` must parse with zero errors, got: $descriptions", descriptions.isEmpty())
    }

    // --- contextual messages on invalid input ---

    fun testLetMissingBindingNameMessage() {
        // `#let = 1`: the assignment has no name in front — a name is expected here.
        assertOnlyError("#let = 1\n", "Expected a binding name")
    }

    fun testUnmatchedParenInCodeBlockMessage() {
        // A stray `)` inside a `{ }` code block is named, not a generic "Unexpected token".
        assertOnlyError("#{ ) }\n", "Unmatched ')'")
    }

    fun testUnmatchedBracketInCodeBlockMessage() {
        assertOnlyError("#{ ] }\n", "Unmatched ']'")
    }

    fun testExpectedExpressionInCodeBlockMessage() {
        // A lone multiplicative operator cannot start a statement — an expression is expected.
        assertOnlyError("#{ * }\n", "Expected an expression")
    }

    fun testExpectedExpressionInCollectionEntryMessage() {
        // `#(,)`: a comma with no value before it — an expression is expected in the entry position.
        assertOnlyError("#(,)\n", "Expected an expression")
    }

    fun testBadCharacterMessage() {
        // The lexer emits BAD_CHARACTER for a lone `!` in code; the parser names the character.
        assertOnlyError("#{ ! }\n", "Unexpected character '!'")
    }

    fun testStrayCloserInMarkupIsNamed() {
        // `#)` leaves a stray `)` that surfaces in markup position; it is named as unmatched.
        assertOnlyError("#)\n", "Unmatched ')'")
    }

    // --- noise avoidance: valid + incomplete-while-typing input stays error-free ---

    fun testEmptyArrayHasNoError() {
        assertZeroErrors("#()\n") // `()` is a valid empty array
    }

    fun testValidLetHasNoError() {
        assertZeroErrors("#let x = 1\n")
    }

    fun testIncompleteLetIsSilentWhileTyping() {
        // Just `#let` (name not typed yet) must NOT be flagged — recovery, not a false error.
        assertZeroErrors("#let\n")
    }

    fun testTrailingBinaryOperatorIsSilentWhileTyping() {
        // `#(1 +)` is the exact mid-typing state before the right operand is entered: stay silent.
        assertZeroErrors("#(1 +)\n")
    }

    fun testUnclosedGroupAtEofIsSilent() {
        // An unterminated group degrades gracefully with no error (avoids editing noise).
        assertZeroErrors("#(1, 2\n")
    }

    fun testStrayBraceInMarkupIsLiteralText() {
        // In markup `}` and `)` are literal text (real Typst), so they never produce an error.
        assertZeroErrors("a } b ) c\n")
    }
}
