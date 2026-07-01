package com.livteam.typninja.language.folding

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Locks the folding behaviour: closed multi-line groups fold; single-line and unclosed groups do not.
 * Calls the builder directly so the assertions are deterministic and independent of editor state.
 */
class TypstFoldingBuilderTest : BasePlatformTestCase() {

    private fun foldCount(text: String): Int {
        val file = myFixture.configureByText("test.typ", text)
        val document = myFixture.editor.document
        return TypstFoldingBuilder().buildFoldRegions(file, document, false).size
    }

    fun testMultilineCodeBlockFolds() {
        assertTrue("a closed multi-line code block must fold", foldCount("#{\n  let x = 1\n}\n") >= 1)
    }

    fun testMultilineParenGroupFolds() {
        assertTrue("a closed multi-line group must fold", foldCount("#table(\n  1, 2,\n  3,\n)\n") >= 1)
    }

    fun testSingleLineGroupDoesNotFold() {
        assertEquals("a single-line group must not fold", 0, foldCount("#foo(1, 2, 3)\n"))
    }

    fun testUnclosedGroupDoesNotFold() {
        assertEquals("an unclosed group must not fold", 0, foldCount("#table(\n  1, 2,\n"))
    }
}
