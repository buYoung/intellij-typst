package com.livteam.typninja.language.references

import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstLetBinding
import com.livteam.typninja.language.psi.TypstReferenceExpression

/**
 * File-local reference-resolution gate for run `typst-lang-intel-03` F1.
 *
 * Each case configures a `.typ` file with a `<caret>` on an identifier USAGE and asserts what
 * `Cmd/Ctrl+Click` (Go To Declaration) would do: `findReferenceAt(caret)?.resolve()` must land on the
 * exact definition (verified by the resolved element's text AND its offset, so shadowing is proven,
 * not just name equality). Definition names, field members, and cross-file imports are asserted to
 * carry no false navigation.
 */
class TypstReferenceResolutionTest : BasePlatformTestCase() {

    /** Resolve the reference under the `<caret>`, or `null` when there is no reference / no target. */
    private fun resolveAtCaret(text: String): PsiElement? {
        val file = myFixture.configureByText("test.typ", text)
        val reference = file.findReferenceAt(myFixture.caretOffset)
        return reference?.resolve()
    }

    private fun hasReferenceAtCaret(text: String): Boolean {
        val file = myFixture.configureByText("test.typ", text)
        return file.findReferenceAt(myFixture.caretOffset) != null
    }

    /** Asserts the caret usage resolves to a definition whose text is [name] at file offset [offset]. */
    private fun assertResolvesTo(text: String, name: String, offset: Int) {
        val target = resolveAtCaret(text)
        assertNotNull("usage must resolve to a definition:\n$text", target)
        assertEquals("resolved definition text", name, target!!.text)
        assertEquals("resolved definition offset (proves the exact declaration)", offset, target.textRange.startOffset)
    }

    // ---- #let variable / function ----

    fun testResolveLetVariable() {
        // `#let data = 1` at offset 5; usage `#data`.
        assertResolvesTo("#let data = 1\n#da<caret>ta\n", "data", 5)
    }

    fun testResolveFunctionCallee() {
        // `#let greet() = 1` — the callee `#greet()` resolves to the function name at offset 5.
        assertResolvesTo("#let greet() = 1\n#gr<caret>eet()\n", "greet", 5)
    }

    fun testResolveInsideCodeBlock() {
        assertResolvesTo("#{\n  let total = 1\n  tot<caret>al + 2\n}\n", "total", "#{\n  let ".length)
    }

    // ---- parameters ----

    fun testResolveFunctionParameterInBody() {
        // `#let f(x) = x` — the param `x` is at offset 7; the body usage resolves to it.
        assertResolvesTo("#let f(x) = <caret>x\n", "x", 7)
    }

    fun testResolveHyphenatedFunctionParametersInBlockBody() {
        val text = """
            #let format-value(data, level) = {
              let prefix = if "prefix" in data { data.prefix } else { "" }
              if level == "normal" { data.value } else { level }
            }
        """.trimIndent()

        assertResolvesTo(text.replace("data {", "da<caret>ta {"), "data", text.indexOf("data"))
        assertResolvesTo(text.replace("level ==", "lev<caret>el =="), "level", text.indexOf("level"))
    }

    fun testResolveClosureParameterInBody() {
        // `#let add = (x, y) => x + y` — param `x` at offset 12 (the `(` is at 11).
        assertResolvesTo("#let add = (x, y) => <caret>x + y\n", "x", 12)
    }

    fun testResolveBareIdentifierClosureParameter() {
        // `#show heading: it => it` — the leading `it` (offset 15) is the parameter.
        assertResolvesTo("#show heading: it => i<caret>t\n", "it", 15)
    }

    fun testShowRuleClosureAndBlockResolveParametersAndLocals() {
        val text = """
            #show heading: item => {
              let local = item
              local
            }
        """.trimIndent()

        assertResolvesTo(text.replace("item\n", "it<caret>em\n"), "item", text.indexOf("item"))
        assertResolvesTo(text.replace("local\n", "lo<caret>cal\n"), "local", text.indexOf("local"))
    }

    // ---- #for loop binding ----

    fun testResolveForLoopBinding() {
        // `#for x in xs { x }` — binding `x` at offset 5; body usage resolves to it.
        assertResolvesTo("#for x in xs { <caret>x }\n", "x", 5)
    }

    fun testForIterableDoesNotResolveToLoopBinding() {
        // The iterable is NOT in the loop-binding scope: `x` in `in x` resolves to the outer #let x.
        assertResolvesTo("#let x = (1,)\n#for x in <caret>x { }\n", "x", 5)
    }

    // ---- shadowing ----

    fun testInnerParameterShadowsOuterLet() {
        // Outer `#let x` at 5; inner param `x` at 18 shadows it inside the function body.
        assertResolvesTo("#let x = 1\n#let f(x) = <caret>x\n", "x", 18)
    }

    fun testNearestDeclarationWinsInSameScope() {
        // Two `#let x`; the usage after both resolves to the second (nearest above), at offset 16.
        assertResolvesTo("#let x = 1\n#let x = 2\n#<caret>x\n", "x", 16)
    }

    fun testBlockLetShadowsFileLet() {
        assertResolvesTo(
            "#let x = 1\n#{\n  let x = 2\n  <caret>x\n}\n",
            "x",
            "#let x = 1\n#{\n  let ".length,
        )
    }

    // ---- graceful non-resolution (no crash, no false navigation) ----

    fun testUndefinedIdentifierResolvesToNull() {
        assertNull("an undefined identifier must resolve to null", resolveAtCaret("#unde<caret>fined\n"))
    }

    fun testCrossFileImportTargetResolvesToNull() {
        // Cross-file `#import` targets need indexing and are out of scope for F1: resolve to null.
        assertNull(
            "an imported symbol has no file-local definition",
            resolveAtCaret("#import \"m.typ\": foo\n#fo<caret>o\n"),
        )
    }

    // ---- no reference on non-usage identifiers (no false navigation) ----

    fun testDefinitionNameCarriesNoReference() {
        assertFalse("a #let definition name must not be a reference", hasReferenceAtCaret("#let da<caret>ta = 1\n"))
    }

    fun testFieldMemberCarriesNoReference() {
        assertFalse("a .field member must not be a reference", hasReferenceAtCaret("#obj.fie<caret>ld\n"))
    }

    fun testNamedArgumentKeyCarriesParameterReference() {
        assertTrue("a known named-argument key must navigate to its parameter", hasReferenceAtCaret("#table(colu<caret>mns: 2)\n"))
    }

    fun testImportClauseSyntaxDoesNotCreateGeneralReferences() {
        myFixture.addFileToProject("base.typ", "#let exported = 1\n#let aliased = 2\n")
        val mainFile = myFixture.addFileToProject(
            "main.typ",
            """
                #import "base.typ": exported, aliased as local, *
                #let exported = 3
                #local
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        val text = myFixture.editor.document.text

        val importPathOffset = text.indexOf("base.typ")
        val importItemOffset = text.indexOf("exported")
        val aliasOffset = text.indexOf("local")
        val globOffset = text.indexOf("*")
        val localUsageOffset = text.lastIndexOf("local")

        assertNotNull("import path must keep its path reference", myFixture.file.findReferenceAt(importPathOffset))
        assertNotNull("import module-side item must keep its import-member reference", myFixture.file.findReferenceAt(importItemOffset))
        assertNull("import alias is a local declaration, not a general reference", myFixture.file.findReferenceAt(aliasOffset))
        assertNull("import glob star is syntax, not a general reference", myFixture.file.findReferenceAt(globOffset))
        assertEquals("aliased", myFixture.file.findReferenceAt(localUsageOffset)!!.resolve()!!.text)
    }

    // ---- PSI wiring sanity ----

    fun testUsageNodeIsReferenceExpressionAndDefinitionIsNameOwner() {
        val file = myFixture.configureByText("test.typ", "#let data = 1\n#da<caret>ta\n")
        val reference = file.findReferenceAt(myFixture.caretOffset)
        assertNotNull(reference)
        assertTrue(
            "the usage element is a TypstReferenceExpression",
            reference!!.element is TypstReferenceExpression,
        )
        val target = reference.resolve()
        assertNotNull(target)
        val letBinding = com.intellij.psi.util.PsiTreeUtil.getParentOfType(target, TypstLetBinding::class.java)
        assertNotNull("the target lives under a TypstLetBinding name owner", letBinding)
        assertEquals("the let binding exposes the bound name", "data", letBinding!!.name)
        assertSame(
            "the target IS the let binding's name identifier",
            letBinding.nameIdentifier, target,
        )
    }

    fun testReferenceExpressionNodeTypeExists() {
        val file = myFixture.configureByText("test.typ", "#data\n")
        val hasReferenceExpr = com.intellij.psi.util.PsiTreeUtil
            .collectElementsOfType(file, TypstReferenceExpression::class.java)
            .any { it.node.elementType == TypstElementTypes.REFERENCE_EXPR }
        assertTrue("a bare code-context identifier must parse to a REFERENCE_EXPR", hasReferenceExpr)
    }
}
