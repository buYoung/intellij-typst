package com.livteam.typninja.language.parser

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * Acceptance-gate tests for run `typst-lang-core-02` P2.
 *
 * 1. The valid-Typst corpus (including the reported user snippet) parses to ZERO [PsiErrorElement]
 *    and the tree spans the whole document.
 * 2. The editing-simulation regression: after typing a character INSIDE a multi-line code block
 *    (the exact scenario that triggered "Unexpected token" + broken highlighting), the file still
 *    parses to zero errors and no code token is mis-classified as markup [TypstTokenTypes.TEXT].
 *    This is the missing test that locks the user-reported bug fixed.
 */
class TypstCorpusParseTest : BasePlatformTestCase() {

    private fun configure(text: String): TypstFile =
        myFixture.configureByText("test.typ", text) as TypstFile

    private fun errorCount(file: TypstFile): Int {
        // Force AST + PSI construction, then count error elements across the whole tree.
        PsiTreeUtil.processElements(file) { true }
        return PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).size
    }

    private fun leafTypes(node: ASTNode, out: MutableList<IElementType>) {
        if (node.firstChildNode == null) {
            out.add(node.elementType)
            return
        }
        var child = node.firstChildNode
        while (child != null) {
            leafTypes(child, out)
            child = child.treeNext
        }
    }

    // --- corpus: valid Typst -> zero PsiErrorElement, whole document covered ---

    private val corpus: List<Pair<String, String>> = listOf(
        "userSnippet" to "#let prefix = if \"prefix\" in data { data.prefix } else { \"\" }\n",
        "multilineArray" to "#let xs = (\n  1,\n  2,\n)\n",
        "multilineCodeBlock" to "#{\n  let x = 1\n  x + 2\n}\n",
        "contentArg" to "#emph[hello] and plain text\n",
        "nestedContent" to "#box[outer [inner] tail]\n",
        "paragraphBoundary" to "para one\n\npara two #let a = 1\n",
        "headingAndList" to "= Title\n\n- one\n- two\n\n+ first\n",
        "termAndRef" to "/ Term: definition\n\nSee @intro for details.\n",
        "labelDef" to "A heading <target> follows.\n",
        "math" to "text $ a + b^2 $ tail\n",
        "measurements" to "#set text(size: 12pt, ratio: 50%)\n",
        "table" to "#table(\n  columns: 2,\n  [A], [B],\n)\n",
        // Reported bug: leading-dot numeric literal in a call argument (`radius: .2em`).
        "leadingDotNumeric" to "#show: checklist.with(fill: luma(95%), stroke: color-primary, radius: .2em)\n",
        "leadingDotFloat" to "#let opacity = .5\n",
        // Reported bug B1: inline `#variable` immediately followed by markup punctuation.
        "inlineVarPunctuation" to "The values are #a, #b, and #c.\nSee #ref; also #value: and #x!\n",
        // Reported bug B2: content block that ends with `#ident`, and inline math with embedded code.
        "contentEndsWithIdent" to "#emph[strong #x tail]\nresult: \$#x\$ then prose.\n",
        // Reported bug M1: inline `#if [..] else [..]` followed by same-line prose.
        "inlineIfElseThenProse" to "#if draft [a draft] else [final] and it continues.\n",
        // Chained field access / method calls must stay code (no atomic mis-termination).
        "chainedCalls" to "#a.b.c and #f(x).method(1) done.\n",
        // Reported bug M2: word-internal `_`/`*` are literal text; real toggles still emphasise.
        "wordBoundaryEmphasis" to "Refer to test_data_set.csv and my_var, then _emph_ and *strong* and 2*3.\n",
    )

    fun testCorpusParsesWithZeroErrorsAndFullCoverage() {
        val failures = StringBuilder()
        for ((name, text) in corpus) {
            val file = configure(text)
            val errors = errorCount(file)
            if (errors != 0) failures.append("[$name] produced $errors PsiErrorElement(s)\n")
            if (file.text != text) failures.append("[$name] tree does not cover the whole document\n")
        }
        assertTrue("corpus must parse with zero errors and full coverage:\n$failures", failures.isEmpty())
    }

    // --- editing simulation: the reported bug (incremental relex inside a multi-line group) ---

    fun testTypingInsideMultilineCodeBlockKeepsZeroErrorsAndNoTextLeak() {
        // A multi-line code block with the caret INSIDE the block, at a mid-group offset — exactly
        // the position from which the old lexer restarted and mis-classified the block as markup.
        configure("#{\n  let total = 1\n  total<caret> + 2\n}\n")

        val before = myFixture.file as TypstFile
        assertEquals("baseline must parse without errors", 0, errorCount(before))

        // Type a character inside the block; the platform relexes/reparses incrementally.
        myFixture.type("s")

        val after = myFixture.file as TypstFile
        assertEquals("editing inside the block must NOT introduce errors", 0, errorCount(after))

        val leaves = ArrayList<IElementType>()
        leafTypes(after.node, leaves)
        assertFalse(
            "no code token may leak into markup TEXT after an in-block edit",
            leaves.contains(TypstTokenTypes.TEXT),
        )
        assertTrue("the closing brace must remain a code RBRACE", leaves.contains(TypstTokenTypes.RBRACE))
        assertTrue("the `let` keyword must remain code", leaves.contains(TypstTokenTypes.KW_LET))
    }

    fun testTypingInsideMultilineArrayKeepsZeroErrorsAndNoTextLeak() {
        configure("#let xs = (\n  1,\n  2<caret>,\n  3,\n)\n")

        assertEquals("baseline array must parse without errors", 0, errorCount(myFixture.file as TypstFile))

        myFixture.type("0") // 2 -> 20, an edit at a mid-group offset

        val after = myFixture.file as TypstFile
        assertEquals("editing inside the array must NOT introduce errors", 0, errorCount(after))

        val leaves = ArrayList<IElementType>()
        leafTypes(after.node, leaves)
        assertFalse("array elements must not leak into markup TEXT", leaves.contains(TypstTokenTypes.TEXT))
        assertTrue("the closing paren must remain a code RPAREN", leaves.contains(TypstTokenTypes.RPAREN))
        assertEquals("all three elements must stay INTEGER_LITERAL", 3, leaves.count { it == TypstTokenTypes.INTEGER_LITERAL })
    }
}
