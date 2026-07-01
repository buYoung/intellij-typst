package com.livteam.typninja.language.formatting

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Behavioural formatter tests. Locks the two properties the earlier phases only argued statically:
 * that formatting is idempotent (a second pass produces no further change) and that markup prose is
 * never rewritten, including on the multi-line code groups R3 made formattable.
 */
class TypstFormatterTest : BasePlatformTestCase() {

    private fun reformat(text: String): String {
        val file = myFixture.configureByText("test.typ", text)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }
        return file.text
    }

    private fun reformatTwice(text: String): Pair<String, String> {
        val file = myFixture.configureByText("test.typ", text)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }
        val once = file.text
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }
        val twice = file.text
        return once to twice
    }

    fun testSingleLineArgumentSpacing() {
        val (once, twice) = reformatTwice("#foo(1,2,3)\n")
        assertEquals("commas must be normalised", "#foo(1, 2, 3)\n", once)
        assertEquals("formatting must be idempotent", once, twice)
    }

    fun testAlreadyFormattedSingleLineIsStable() {
        val input = "#foo(1, 2, 3)\n"
        assertEquals("already-formatted input must be unchanged", input, reformat(input))
    }

    fun testMultilineTableGetsIndentedAndIsStable() {
        val input = "#table(\ncolumns: 2,\n[A], [B],\n)\n"
        val (once, twice) = reformatTwice(input)
        assertTrue("multi-line group must be re-indented", once != input)
        val columnsLine = once.lines().first { it.contains("columns") }
        assertTrue("the group body must be indented", columnsLine.first().isWhitespace())
        assertEquals("multi-line formatting must be idempotent", once, twice)
    }

    fun testMultilineCodeBlockGetsIndentedAndIsStable() {
        val input = "#{\nlet x = 1\n}\n"
        val (once, twice) = reformatTwice(input)
        val bodyLine = once.lines().first { it.contains("let x") }
        assertTrue("the code-block body must be indented", bodyLine.first().isWhitespace())
        assertEquals("multi-line code-block formatting must be idempotent", once, twice)
    }

    fun testLetBindingAssignmentSpacingIsNormalised() {
        val (once, twice) = reformatTwice("#let a=1\n")
        assertEquals("assignment must gain a space on each side of =", "#let a = 1\n", once)
        assertEquals("statement spacing must be idempotent", once, twice)
    }

    fun testMultilineArrayInsideLetIsIndentedAndStable() {
        val input = "#let xs = (\n1,\n2,\n3,\n)\n"
        val (once, twice) = reformatTwice(input)
        val bodyLine = once.lines().first { it.trim() == "1," }
        assertTrue("the array body must be indented", bodyLine.first().isWhitespace())
        assertEquals("multi-line array formatting must be idempotent", once, twice)
    }

    fun testMultilineDictIsIndentedAndStable() {
        val input = "#let cfg = (\na: 1,\nb: 2,\n)\n"
        val (once, twice) = reformatTwice(input)
        val bodyLine = once.lines().first { it.contains("a: 1") }
        assertTrue("the dictionary body must be indented", bodyLine.first().isWhitespace())
        assertTrue("named entries keep `key: value` spacing", once.contains("a: 1"))
        assertEquals("multi-line dictionary formatting must be idempotent", once, twice)
    }

    fun testMultilineFuncCallArgsIsIndentedAndStable() {
        val input = "#figure(\nimage(\"a.png\"),\ncaption: \"c\",\n)\n"
        val (once, twice) = reformatTwice(input)
        val bodyLine = once.lines().first { it.contains("caption") }
        assertTrue("the argument list body must be indented", bodyLine.first().isWhitespace())
        assertEquals("multi-line argument formatting must be idempotent", once, twice)
    }

    fun testMarkupProseIsPreserved() {
        val input = "This  is    prose with *emphasis*  and  odd   spacing.\n"
        assertEquals("markup prose spacing must never be rewritten", input, reformat(input))
    }

    fun testRepresentativeFileIsIdempotent() {
        val input = """
            = Title

            Some prose with #emph[Why?] inline.

            #let numbers = (1, 2, 3)

            #table(
            columns: 2,
            [A], [B],
            )

            $ a + b $
        """.trimIndent() + "\n"
        val (once, twice) = reformatTwice(input)
        assertEquals("whole-file formatting must converge", once, twice)
        assertTrue("inline prose must be preserved", once.contains("#emph[Why?]"))
    }
}
