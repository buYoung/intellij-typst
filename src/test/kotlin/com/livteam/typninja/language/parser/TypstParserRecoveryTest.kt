package com.livteam.typninja.language.parser

import com.intellij.lang.ASTNode
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstFile

/**
 * Golden parser-recovery tests. Uses the real registered parser (via the light fixture) rather than
 * a gold-file [com.intellij.testFramework.ParsingTestCase], so it exercises the exact production
 * wiring and stays robust to incidental tree-shape changes while still locking the R3 invariants:
 * a [TypstFile] is always produced, the tree covers the whole document, and pathological / incomplete
 * input never throws (no StackOverflow).
 */
class TypstParserRecoveryTest : BasePlatformTestCase() {

    /** Walks the whole AST (forcing it to be built) and returns the node count. */
    private fun walk(node: ASTNode): Int {
        var count = 1
        var child = node.firstChildNode
        while (child != null) {
            count += walk(child)
            child = child.treeNext
        }
        return count
    }

    private fun containsElementType(node: ASTNode, target: IElementType): Boolean {
        if (node.elementType == target) return true
        var child = node.firstChildNode
        while (child != null) {
            if (containsElementType(child, target)) return true
            child = child.treeNext
        }
        return false
    }

    private fun parse(text: String): TypstFile {
        val file = myFixture.configureByText("test.typ", text) as TypstFile
        // Force AST construction so the parser actually runs.
        walk(file.node)
        return file
    }

    fun testProducesTypstFileCoveringWholeDocument() {
        val text = "= Heading\n\nSome prose #let x = 1\n\n$ a + b $\n"
        val file = parse(text)
        assertEquals("the file must be a Typst PSI file", "Typst", file.fileType.name)
        assertEquals("the tree must cover the whole document", text, file.text)
    }

    fun testUnclosedStringYieldsValidTree() {
        val text = "#let s = \"oops\nplain text follows\n"
        val file = parse(text)
        assertEquals(text, file.text)
    }

    fun testUnclosedCodeGroupYieldsValidTree() {
        val text = "#table(\n  1, 2,\n"
        val file = parse(text)
        assertEquals(text, file.text)
    }

    fun testUnclosedMathDoesNotSwallowFollowingParagraph() {
        val text = "$ a + b\n\nThis paragraph must survive.\n"
        val file = parse(text)
        assertEquals(text, file.text)
        assertTrue("a MATH region must be produced", containsElementType(file.node, TypstElementTypes.MATH))
    }

    fun testDeeplyNestedParensDoNotStackOverflow() {
        // Thousands deep — must produce a bounded tree, not a StackOverflowError.
        val text = "#" + "(".repeat(3000)
        val file = parse(text)
        assertEquals(text, file.text)
    }

    fun testDeeplyNestedContentBlocksDoNotStackOverflow() {
        val text = "[".repeat(3000)
        val file = parse(text)
        assertEquals(text, file.text)
    }
}
