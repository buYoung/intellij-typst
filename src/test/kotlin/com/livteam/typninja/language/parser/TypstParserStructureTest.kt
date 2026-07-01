package com.livteam.typninja.language.parser

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstTokenTypes as T

/**
 * AST-structure gate for run `typst-lang-core-02` P3.
 *
 * These assert REAL parse structure (specific composite nodes and their nesting), not just
 * zero-error / full coverage — that is what proves the parser is a genuine recursive-descent grammar
 * rather than a region grouper. Each case also asserts zero [PsiErrorElement] so valid Typst is never
 * flagged.
 */
class TypstParserStructureTest : BasePlatformTestCase() {

    private fun parse(text: String): TypstFile {
        val file = myFixture.configureByText("test.typ", text) as TypstFile
        PsiTreeUtil.processElements(file) { true } // force the whole AST
        return file
    }

    private fun errorCount(file: TypstFile): Int =
        PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).size

    /** All AST nodes of [type] (composite or leaf) anywhere under [root]. */
    private fun nodesOf(root: ASTNode, type: IElementType): List<ASTNode> {
        val out = ArrayList<ASTNode>()
        fun walk(node: ASTNode) {
            if (node.elementType == type) out.add(node)
            var child = node.firstChildNode
            while (child != null) { walk(child); child = child.treeNext }
        }
        walk(root)
        return out
    }

    private fun contains(root: ASTNode, type: IElementType): Boolean = nodesOf(root, type).isNotEmpty()

    private fun descendantContains(node: ASTNode, type: IElementType): Boolean =
        nodesOf(node, type).any { it !== node }

    private fun firstChildTypes(node: ASTNode): List<IElementType> {
        val out = ArrayList<IElementType>()
        var child = node.firstChildNode
        while (child != null) { out.add(child.elementType); child = child.treeNext }
        return out
    }

    private fun assertNoErrors(file: TypstFile, name: String) =
        assertEquals("$name must parse with zero errors", 0, errorCount(file))

    // --- the reported user snippet: LET_BINDING containing a CONDITIONAL ---

    fun testUserSnippetLetContainsConditional() {
        val file = parse("#let prefix = if \"prefix\" in data { data.prefix } else { \"\" }\n")
        assertNoErrors(file, "userSnippet")
        val root = file.node

        val lets = nodesOf(root, E.LET_BINDING)
        assertEquals("exactly one let binding", 1, lets.size)
        val let = lets.single()
        assertTrue("the let binding must contain a conditional", descendantContains(let, E.CONDITIONAL))

        val conditional = nodesOf(let, E.CONDITIONAL).single()
        assertTrue("the conditional must contain the `in` comparison as a BINARY", contains(conditional, E.BINARY))
        assertEquals("two code blocks (then / else)", 2, nodesOf(conditional, E.CODE_BLOCK).size)
        assertTrue("the then-branch must contain a FIELD_ACCESS (data.prefix)", contains(conditional, E.FIELD_ACCESS))
        assertTrue("the whole thing is a #-code expression", contains(root, E.CODE_EXPRESSION))
    }

    // --- collections: array / dict / parenthesized ---

    fun testArrayLiteral() {
        val file = parse("#let xs = (1, 2, 3)\n")
        assertNoErrors(file, "array")
        val root = file.node
        assertTrue("a let with a (1, 2, 3) value must yield an ARRAY", contains(root, E.ARRAY))
        assertFalse("it is not a dict", contains(root, E.DICT))
        val array = nodesOf(root, E.ARRAY).single()
        assertEquals("the array has three integer elements", 3, nodesOf(array, T.INTEGER_LITERAL).size)
    }

    fun testDictLiteral() {
        val file = parse("#(a: 1, b: 2)\n")
        assertNoErrors(file, "dict")
        val root = file.node
        assertTrue("named entries make it a DICT", contains(root, E.DICT))
        assertFalse("it is not an array", contains(root, E.ARRAY))
        assertEquals("two named entries", 2, nodesOf(root, E.NAMED).size)
    }

    fun testParenthesizedSingleExpression() {
        val file = parse("#(1 + 2)\n")
        assertNoErrors(file, "parenthesized")
        val root = file.node
        assertTrue("a single expression in parens is PARENTHESIZED", contains(root, E.PARENTHESIZED))
        assertTrue("it wraps a BINARY", contains(root, E.BINARY))
        assertFalse("it is not an array", contains(root, E.ARRAY))
    }

    // --- function call with args and a trailing content argument ---

    fun testFuncCallWithArgsAndTrailingContent() {
        val file = parse("#f(a: 1)[body]\n")
        assertNoErrors(file, "funcCall")
        val root = file.node
        val calls = nodesOf(root, E.FUNC_CALL)
        assertEquals("one function call", 1, calls.size)
        val call = calls.single()
        val children = firstChildTypes(call)
        assertTrue("the call has an ARGS group", children.contains(E.ARGS))
        assertTrue("the call has a trailing CONTENT_BLOCK", children.contains(E.CONTENT_BLOCK))
        assertTrue("the args contain a named argument", contains(call, E.NAMED))
    }

    // --- closure with a parameter list (code context via a let binding) ---

    fun testClosureWithParams() {
        val file = parse("#let add = (x, y) => x + y\n")
        assertNoErrors(file, "closure")
        val root = file.node
        assertTrue("a `(x, y) =>` value must be a CLOSURE", contains(root, E.CLOSURE))
        val closure = nodesOf(root, E.CLOSURE).single()
        assertTrue("the closure must own a PARAMS list", contains(closure, E.PARAMS))
        assertTrue("the closure body is a BINARY", contains(closure, E.BINARY))
        assertFalse("the (x, y) must NOT be left as an array", contains(root, E.ARRAY))
    }

    // --- markup: heading / list / enum / emphasis / strong / ref ---

    fun testMarkupStructures() {
        val file = parse("= Title\n\n- one\n- two\n\n+ first\n\nText _em_ and *strong* see @ref.\n")
        assertNoErrors(file, "markup")
        val root = file.node
        assertTrue("heading", contains(root, E.HEADING))
        assertEquals("two list items", 2, nodesOf(root, E.LIST_ITEM).size)
        assertTrue("enum item", contains(root, E.ENUM_ITEM))
        assertTrue("emphasis", contains(root, E.EMPH))
        assertTrue("strong", contains(root, E.STRONG))
        assertTrue("reference", contains(root, E.REF))
    }

    fun testTermItem() {
        val file = parse("/ Term: the definition\n")
        assertNoErrors(file, "term")
        assertTrue("a term list item", contains(file.node, E.TERM_ITEM))
    }

    // --- M2: word-internal `_`/`*` are literal text, not emphasis/strong toggles ---

    fun testWordInternalMarkersDoNotFormEmphasis() {
        val file = parse("Refer to test_data_set and 2*3 here.\n")
        assertNoErrors(file, "wordInternalMarkers")
        val root = file.node
        assertFalse("word-internal `_` must not form an EMPH node", contains(root, E.EMPH))
        assertFalse("word-internal `*` must not form a STRONG node", contains(root, E.STRONG))

        // Real, word-boundary toggles must still produce EMPH / STRONG nodes.
        val real = parse("Text _em_ and *strong* here.\n")
        assertNoErrors(real, "realEmphasis")
        assertTrue("a real `_em_` still forms an EMPH node", contains(real.node, E.EMPH))
        assertTrue("a real `*strong*` still forms a STRONG node", contains(real.node, E.STRONG))
    }

    // --- statements: set / show / for / import ---

    fun testStatements() {
        val set = parse("#set text(size: 12pt)\n")
        assertNoErrors(set, "set")
        assertTrue("set rule", contains(set.node, E.SET_RULE))
        assertTrue("set rule wraps a call", contains(set.node, E.FUNC_CALL))

        val show = parse("#show heading: it => it\n")
        assertNoErrors(show, "show")
        assertTrue("show rule", contains(show.node, E.SHOW_RULE))

        val forLoop = parse("#for x in nums { x }\n")
        assertNoErrors(forLoop, "for")
        assertTrue("for loop", contains(forLoop.node, E.FOR_LOOP))

        val import = parse("#import \"m.typ\": a, b\n")
        assertNoErrors(import, "import")
        assertTrue("module import", contains(import.node, E.MODULE_IMPORT))
        assertTrue("import items", contains(import.node, E.IMPORT_ITEMS))
    }

    // --- unary and binary precedence ---

    fun testUnaryAndBinaryPrecedence() {
        val file = parse("#(-1 + 2 * 3)\n")
        assertNoErrors(file, "precedence")
        val root = file.node
        assertTrue("a unary minus", contains(root, E.UNARY))
        // Precedence: `+` is the outer binary, `*` binds tighter and is nested in its right operand.
        val binaries = nodesOf(root, E.BINARY)
        assertTrue("at least two binary nodes (additive over multiplicative)", binaries.size >= 2)
        assertTrue("the additive binary spans '-1 + 2 * 3'", binaries.any { it.text.trim() == "-1 + 2 * 3" })
        assertTrue("a multiplicative binary is nested", binaries.any { it.text.trim() == "2 * 3" })
    }

    // --- inline math: equation with a nested delimited group, no errors ---

    fun testMathEquation() {
        val file = parse("text $ a + (b/2)^2 $ tail\n")
        assertNoErrors(file, "math")
        val root = file.node
        assertTrue("an equation region", contains(root, E.MATH))
        assertTrue("a delimited math group", contains(root, E.MATH_DELIMITED))
    }
}
