package com.livteam.typninja.language.highlighting

import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.livteam.typninja.language.psi.TypstElementTypes
import java.awt.Font

/**
 * Locks the contextual-modifier contract: `*strong*` maps to a bold key and `_emph_` to an italic
 * key, both via [TypstAnnotator]. The mapping is tested directly (no editor round-trip) and against
 * the real parser output, and the keys are asserted to carry the expected font style only.
 */
class TypstAnnotatorTest : BasePlatformTestCase() {

    fun testKeyMappingIsExact() {
        assertSame(
            "STRONG node must map to the strong key",
            TypstTextAttributeKeys.STRONG, TypstAnnotator.keyFor(TypstElementTypes.STRONG),
        )
        assertSame(
            "EMPH node must map to the emphasis key",
            TypstTextAttributeKeys.EMPH, TypstAnnotator.keyFor(TypstElementTypes.EMPH),
        )
        assertNull("unrelated node types must not be annotated", TypstAnnotator.keyFor(TypstElementTypes.HEADING))
        assertNull("null element type must be tolerated", TypstAnnotator.keyFor(null))
    }

    fun testStrongKeyIsBoldAndEmphKeyIsItalic() {
        val strong = TypstTextAttributeKeys.STRONG.defaultAttributes
        val emph = TypstTextAttributeKeys.EMPH.defaultAttributes
        assertNotNull("strong key must carry default attributes", strong)
        assertNotNull("emphasis key must carry default attributes", emph)
        assertTrue("strong must be bold", (strong!!.fontType and Font.BOLD) != 0)
        assertTrue("emphasis must be italic", (emph!!.fontType and Font.ITALIC) != 0)
        // Contextual modifiers must not force a color (FDD 9.2: stay theme-friendly).
        assertNull("strong must not hard-set a foreground color", strong.foregroundColor)
        assertNull("emphasis must not hard-set a foreground color", emph.foregroundColor)
    }

    fun testRealMarkupProducesAnnotatableNodes() {
        val file = myFixture.configureByText("test.typ", "Prose with *bold* and _italic_ inline.\n")
        val types = collectElementTypes(file)
        assertTrue("parser must produce a STRONG node", types.contains(TypstElementTypes.STRONG))
        assertTrue("parser must produce an EMPH node", types.contains(TypstElementTypes.EMPH))
        // Every emphasis toggle node the parser produced must resolve to a modifier key.
        assertSame(TypstTextAttributeKeys.STRONG, TypstAnnotator.keyFor(TypstElementTypes.STRONG))
        assertSame(TypstTextAttributeKeys.EMPH, TypstAnnotator.keyFor(TypstElementTypes.EMPH))
    }

    // ---- semantic highlighting (PSI-driven) ----

    fun testFuncCallCalleeGetsFunctionCallKey() {
        val file = myFixture.configureByText("test.typ", "#table(x)\n")
        val call = firstNodeOf(file, TypstElementTypes.FUNC_CALL)
        val highlights = TypstAnnotator.semanticHighlights(call)
        assertEquals("a simple call contributes exactly one semantic highlight", 1, highlights.size)
        val (range, key) = highlights.single()
        assertEquals("the callee identifier is highlighted", "table", rangeText(file, range))
        assertSame("callee uses the function-call key", TypstTextAttributeKeys.FUNCTION_CALL, key)
    }

    fun testMethodCallCalleeGetsFunctionCallKey() {
        val file = myFixture.configureByText("test.typ", "#obj.method(1)\n")
        val call = firstNodeOf(file, TypstElementTypes.FUNC_CALL)
        val highlights = TypstAnnotator.semanticHighlights(call)
        assertEquals("a method call highlights only the method name", 1, highlights.size)
        val (range, key) = highlights.single()
        assertEquals("the method name after the dot is highlighted", "method", rangeText(file, range))
        assertSame("method name uses the function-call key", TypstTextAttributeKeys.FUNCTION_CALL, key)
        // A standalone field access (not used as a call) must NOT be colored as a call.
        val fieldAccess = firstNodeOf(file, TypstElementTypes.FIELD_ACCESS)
        assertTrue("bare field access contributes no highlight", TypstAnnotator.semanticHighlights(fieldAccess).isEmpty())
    }

    fun testLetVariableGetsVariableDefinitionKey() {
        val file = myFixture.configureByText("test.typ", "#let v = 1\n")
        val let = firstNodeOf(file, TypstElementTypes.LET_BINDING)
        val highlights = TypstAnnotator.semanticHighlights(let)
        assertEquals(1, highlights.size)
        val (range, key) = highlights.single()
        assertEquals("v", rangeText(file, range))
        assertSame("a plain let name uses the variable-definition key", TypstTextAttributeKeys.VARIABLE_DEFINITION, key)
    }

    fun testVariableDefinitionKeyUsesThemeFallbackOnly() {
        assertSame(
            "variable definitions must inherit the current scheme's identifier color",
            DefaultLanguageHighlighterColors.IDENTIFIER,
            TypstTextAttributeKeys.VARIABLE_DEFINITION.fallbackAttributeKey,
        )
    }

    fun testLetFunctionGetsDeclarationKeyAndParamsGetParameterKey() {
        val file = myFixture.configureByText("test.typ", "#let f(x) = x\n")
        val let = firstNodeOf(file, TypstElementTypes.LET_BINDING)
        val letHighlights = TypstAnnotator.semanticHighlights(let)
        assertEquals(1, letHighlights.size)
        assertEquals("f", rangeText(file, letHighlights.single().first))
        assertSame("a `let f(...)` name uses the function-declaration key",
            TypstTextAttributeKeys.FUNCTION_DECLARATION, letHighlights.single().second)

        val params = firstNodeOf(file, TypstElementTypes.PARAMS)
        val paramHighlights = TypstAnnotator.semanticHighlights(params)
        assertEquals(1, paramHighlights.size)
        assertEquals("x", rangeText(file, paramHighlights.single().first))
        assertSame("the parameter uses the parameter key", TypstTextAttributeKeys.PARAMETER, paramHighlights.single().second)
    }

    fun testClosureParamsGetParameterKey() {
        val file = myFixture.configureByText("test.typ", "#let add = (x, y) => x + y\n")
        val params = firstNodeOf(file, TypstElementTypes.PARAMS)
        val highlights = TypstAnnotator.semanticHighlights(params)
        assertEquals("both closure parameters are highlighted", 2, highlights.size)
        assertEquals(listOf("x", "y"), highlights.map { rangeText(file, it.first) })
        assertTrue("all parameters use the parameter key",
            highlights.all { it.second === TypstTextAttributeKeys.PARAMETER })
    }

    fun testSingleIdentifierClosureParamGetsParameterKey() {
        val file = myFixture.configureByText("test.typ", "#show heading: it => it\n")
        val closure = firstNodeOf(file, TypstElementTypes.CLOSURE)
        val highlights = TypstAnnotator.semanticHighlights(closure)
        assertEquals(1, highlights.size)
        assertEquals("it", rangeText(file, highlights.single().first))
        assertSame(TypstTextAttributeKeys.PARAMETER, highlights.single().second)
    }

    fun testNamedCallArgumentGetsNamedArgumentKey() {
        val file = myFixture.configureByText("test.typ", "#table(columns: 2)\n")
        val named = firstNodeOf(file, TypstElementTypes.NAMED)
        val highlights = TypstAnnotator.semanticHighlights(named)
        assertEquals(1, highlights.size)
        assertEquals("columns", rangeText(file, highlights.single().first))
        assertSame("a named call argument uses the named-argument key",
            TypstTextAttributeKeys.NAMED_ARGUMENT, highlights.single().second)
    }

    fun testDictEntryIsNotColoredAsNamedArgument() {
        // A `NAMED` used as a dict entry (parent is DICT, not ARGS) must not get the call-argument key.
        val file = myFixture.configureByText("test.typ", "#(a: 1)\n")
        val named = firstNodeOf(file, TypstElementTypes.NAMED)
        assertTrue("dict entries contribute no semantic highlight", TypstAnnotator.semanticHighlights(named).isEmpty())
    }

    // ---- reference-usage (read) highlighting: colored by what the F1 reference resolves to ----

    fun testVariableUsageGetsVariableKey() {
        val file = myFixture.configureByText("test.typ", "#let v = 1\n#(v + 1)\n")
        val usage = firstNodeOf(file, TypstElementTypes.REFERENCE_EXPR)
        val highlights = TypstAnnotator.semanticHighlights(usage)
        assertEquals("a resolved variable read contributes exactly one highlight", 1, highlights.size)
        val (range, key) = highlights.single()
        assertEquals("the variable read identifier is highlighted", "v", rangeText(file, range))
        assertSame("a read resolving to a plain #let uses the variable key",
            TypstTextAttributeKeys.VARIABLE, key)
    }

    fun testFunctionUsageAsValueGetsFunctionCallKey() {
        // A function read that is NOT a call site (passed as a value) goes through the reference path.
        val file = myFixture.configureByText("test.typ", "#let f() = 1\n#(f)\n")
        val usage = firstNodeOf(file, TypstElementTypes.REFERENCE_EXPR)
        val highlights = TypstAnnotator.semanticHighlights(usage)
        assertEquals(1, highlights.size)
        val (range, key) = highlights.single()
        assertEquals("f", rangeText(file, range))
        assertSame("a read resolving to a #let function uses the function-call key",
            TypstTextAttributeKeys.FUNCTION_CALL, key)
    }

    fun testFunctionCalleeUsageIsColoredByTheCallNotTheReferencePath() {
        val file = myFixture.configureByText("test.typ", "#let f() = 1\n#f()\n")
        // The callee-position usage is deliberately skipped by the reference path...
        val calleeUsage = firstNodeOf(file, TypstElementTypes.REFERENCE_EXPR)
        assertTrue("a callee-position usage is not colored via the reference path",
            TypstAnnotator.semanticHighlights(calleeUsage).isEmpty())
        // ...and is colored exactly once, as a function call, by the call owner.
        val call = firstNodeOf(file, TypstElementTypes.FUNC_CALL)
        val callHighlights = TypstAnnotator.semanticHighlights(call)
        assertEquals(1, callHighlights.size)
        assertEquals("f", rangeText(file, callHighlights.single().first))
        assertSame(TypstTextAttributeKeys.FUNCTION_CALL, callHighlights.single().second)
    }

    fun testParameterUsageGetsParameterKey() {
        val file = myFixture.configureByText("test.typ", "#let g(x) = x + 1\n")
        val usage = firstNodeOf(file, TypstElementTypes.REFERENCE_EXPR)
        val highlights = TypstAnnotator.semanticHighlights(usage)
        assertEquals(1, highlights.size)
        val (range, key) = highlights.single()
        assertEquals("x", rangeText(file, range))
        assertSame("a read resolving to a parameter uses the parameter key",
            TypstTextAttributeKeys.PARAMETER, key)
    }

    fun testUnresolvedUsageGetsNoSemanticKey() {
        // Built-in / cross-file / undefined: no file-local definition → no color, and NOT an error.
        val file = myFixture.configureByText("test.typ", "#(missingName + 1)\n")
        val usage = firstNodeOf(file, TypstElementTypes.REFERENCE_EXPR)
        assertTrue("an unresolved read contributes no semantic highlight",
            TypstAnnotator.semanticHighlights(usage).isEmpty())
    }

    private fun rangeText(file: PsiFile, range: com.intellij.openapi.util.TextRange): String =
        file.text.substring(range.startOffset, range.endOffset)

    private fun firstNodeOf(file: PsiFile, type: IElementType): ASTNode {
        var found: ASTNode? = null
        fun walk(node: ASTNode) {
            if (found != null) return
            if (node.elementType == type) { found = node; return }
            var child = node.firstChildNode
            while (child != null) { walk(child); child = child.treeNext }
        }
        walk(file.node)
        return found ?: error("no $type node found in: ${file.text}")
    }

    private fun collectElementTypes(file: PsiFile): List<IElementType> {
        val result = ArrayList<IElementType>()
        fun walk(node: ASTNode) {
            result.add(node.elementType)
            var child = node.firstChildNode
            while (child != null) {
                walk(child)
                child = child.treeNext
            }
        }
        walk(file.node)
        return result
    }
}
