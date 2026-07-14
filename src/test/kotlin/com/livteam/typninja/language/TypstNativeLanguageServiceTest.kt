package com.livteam.typninja.language

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.livteam.typninja.language.editor.TypstWordSelectionHandler
import com.livteam.typninja.language.psi.TypstReferenceExpression

class TypstNativeLanguageServiceTest : BasePlatformTestCase() {

    fun testFirstEditorSelectWordActionSelectsHyphenatedTypstIdentifier() {
        myFixture.configureByText("test.typ", "#let exported-va<caret>lue = 1\n#exported-value\n")

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET)

        assertEquals("exported-value", myFixture.editor.selectionModel.selectedText)
    }

    fun testRegisteredWordSelectionHandlerProvidesHyphenatedTypstIdentifierRange() {
        myFixture.configureByText("test.typ", "#let exported-va<caret>lue = 1\n#exported-value\n")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val ranges = TypstWordSelectionHandler().select(
            element,
            myFixture.editor.document.charsSequence,
            myFixture.caretOffset,
            myFixture.editor,
        )

        val selectedTexts = ranges.map { myFixture.editor.document.text.substring(it.startOffset, it.endOffset) }

        assertTrue("Typst word-selection handler must expose the whole identifier token", "exported-value" in selectedTexts)
        assertTrue("caret must be inside a Typst identifier token", element.parent is TypstReferenceExpression || element.text == "exported-value")
    }

    fun testGoToDeclarationResolvesHyphenatedVariableUsage() {
        assertReferenceResolvesTo(
            "#let exported-value = 1\n#exported-va<caret>lue\n",
            "exported-value",
            "#let ".length,
        )
    }

    fun testImportedSymbolUsageResolvesToExportedDefinition() {
        myFixture.addFileToProject("lib.typ", "#let exported-value = 42\n#let hidden = 0\n")
        val mainFile = myFixture.addFileToProject(
            "main.typ",
            "#import \"lib.typ\": exported-value\n#exported-va<caret>lue\n",
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val target = referenceAtCaret()?.resolve()

        assertNotNull("imported usage must resolve", target)
        assertEquals("exported-value", target!!.text)
        assertEquals("#let ".length, target.textRange.startOffset)
        assertEquals("lib.typ", target.containingFile.name)
    }

    fun testImportMemberReferenceResolvesToExportedDefinition() {
        myFixture.addFileToProject("lib.typ", "#let exported = 42\n")
        val mainFile = myFixture.addFileToProject("main.typ", "#import \"lib.typ\": exp<caret>orted\n")
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val target = referenceAtCaret()?.resolve()

        assertNotNull("import item must resolve to the exported symbol", target)
        assertEquals("exported", target!!.text)
        assertEquals("lib.typ", target.containingFile.name)
    }

    fun testMissingImportMemberReferenceResolvesToNull() {
        myFixture.addFileToProject("lib.typ", "#let exported = 42\n")
        val mainFile = myFixture.addFileToProject("main.typ", "#import \"lib.typ\": miss<caret>ing\n#missing\n")
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertNull("missing import item must not navigate", referenceAtCaret()?.resolve())
    }

    fun testImportAliasUsageResolvesToOriginalExportedDefinition() {
        myFixture.addFileToProject("lib.typ", "#let exported = 42\n")
        val mainFile = myFixture.addFileToProject(
            "main.typ",
            "#import \"lib.typ\": exported as local\n#lo<caret>cal\n",
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val target = referenceAtCaret()?.resolve()

        assertNotNull("alias usage must resolve", target)
        assertEquals("exported", target!!.text)
        assertEquals("lib.typ", target.containingFile.name)
    }

    fun testImportAliasModuleSideResolvesAndLocalSideCarriesNoImportReference() {
        myFixture.addFileToProject("lib.typ", "#let exported = 42\n")
        val mainFile = myFixture.addFileToProject(
            "main.typ",
            "#import \"lib.typ\": exp<caret>orted as local\n",
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val moduleSideTarget = referenceAtCaret()?.resolve()
        assertNotNull("module-side import item must resolve", moduleSideTarget)
        assertEquals("exported", moduleSideTarget!!.text)
        assertEquals("lib.typ", moduleSideTarget.containingFile.name)

        val aliasOffset = myFixture.editor.document.text.indexOf("local")
        assertNull("local alias name in the import clause is a declaration, not a reference", myFixture.file.findReferenceAt(aliasOffset))
    }

    fun testGlobImportUsageResolvesToExportedDefinition() {
        myFixture.addFileToProject("lib.typ", "#let exported = 42\n")
        val mainFile = myFixture.addFileToProject("main.typ", "#import \"lib.typ\": *\n#exp<caret>orted\n")
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val target = referenceAtCaret()?.resolve()

        assertNotNull("glob-imported usage must resolve", target)
        assertEquals("exported", target!!.text)
        assertEquals("lib.typ", target.containingFile.name)
    }

    fun testLocalDefinitionShadowsImportedSymbol() {
        myFixture.addFileToProject("lib.typ", "#let value = 1\n")
        val mainFile = myFixture.addFileToProject(
            "main.typ",
            "#import \"lib.typ\": value\n#let value = 2\n#va<caret>lue\n",
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val target = referenceAtCaret()?.resolve()

        assertNotNull("local symbol must resolve", target)
        assertEquals("value", target!!.text)
        assertEquals(myFixture.editor.document.text.indexOf("#let value = 2") + "#let ".length, target.textRange.startOffset)
        assertEquals("main.typ", target.containingFile.name)
    }

    fun testLabelReferenceResolvesToLabelDefinition() {
        assertReferenceResolvesTo("= Intro <intro>\nSee @in<caret>tro.\n", "<intro>", "= Intro ".length)
    }

    fun testMissingLabelReferenceResolvesToNull() {
        myFixture.configureByText("test.typ", "See @miss<caret>ing.\n")

        assertNull("missing label reference must not navigate", referenceAtCaret()?.resolve())
    }

    fun testImportPathReferenceResolvesToModuleFile() {
        myFixture.addFileToProject("lib.typ", "#let exported = 42\n")
        val mainFile = myFixture.addFileToProject("main.typ", "#import \"li<caret>b.typ\": exported\n")
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val target = referenceAtCaret()?.resolve()

        assertNotNull("import path must resolve to the module file", target)
        assertEquals("lib.typ", target!!.containingFile.name)
    }

    fun testRelativePathStringProvidesSingleReference() {
        myFixture.addFileToProject("assets/logo.png", "not really an image")
        val mainFile = myFixture.addFileToProject("main.typ", "#image(\"assets/lo<caret>go.png\")\n")
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val stringElement = element.parent

        assertEquals("relative path string must expose exactly one path reference", 1, stringElement.references.size)
        assertEquals("logo.png", referenceAtCaret()?.resolve()!!.containingFile.name)
    }

    fun testUrlLinkProvidesSingleWebReference() {
        myFixture.configureByText("test.typ", "See https://example.co<caret>m/docs now.\n")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val markupElement = element.parent
        val urlReference = referenceAtCaret()

        assertNotNull("URL link must be discoverable through findReferenceAt", urlReference)
        assertEquals("URL markup must expose exactly one web reference", 1, markupElement.references.size)
        assertSame("URL link must resolve from the containing markup node", markupElement, urlReference!!.element)
    }

    fun testCompletionOffersLocalImportedLabelAndBuiltinNames() {
        myFixture.addFileToProject("lib.typ", "#let exported = 42\n")
        val mainFile = myFixture.addFileToProject(
            "main.typ",
            """
                #import "lib.typ": exported
                #let local-value = 1
                <intro>
                #<caret>
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val lookupStrings = myFixture.completeBasic().orEmpty().map { it.lookupString }.toSet()

        assertTrue("local symbols must complete", "local-value" in lookupStrings)
        assertTrue("imported symbols must complete", "exported" in lookupStrings)
        assertTrue("known builtins must complete", "table" in lookupStrings)
    }

    fun testLabelCompletionOffersKnownLabels() {
        myFixture.configureByText("test.typ", "<intro>\nSee @in<caret>\n")

        val lookupStrings = myFixture.completeBasic().orEmpty().map { it.lookupString }.toSet()

        assertTrue(
            "label completion must offer the label name, got $lookupStrings",
            "@intro" in lookupStrings || "intro" in lookupStrings,
        )
    }

    fun testImportMemberCompletionOffersExportsFromModule() {
        myFixture.addFileToProject("lib.typ", "#let exported = 42\n#let other = 1\n")
        val mainFile = myFixture.addFileToProject("main.typ", "#import \"lib.typ\": exp<caret>\n")
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val lookupStrings = myFixture.completeBasic().orEmpty().map { it.lookupString }.toSet()
        val documentText = myFixture.editor.document.text

        assertTrue(
            "import member completion must offer or insert exported names, got $lookupStrings in `$documentText`",
            "exported" in lookupStrings || documentText.contains(": exported"),
        )
    }

    fun testPathCompletionOffersRelativeTypstFiles() {
        myFixture.addFileToProject("lib.typ", "#let exported = 42\n")
        myFixture.addFileToProject("notes.txt", "not a module")
        val mainFile = myFixture.addFileToProject("main.typ", "#import \"li<caret>\"\n")
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val lookupStrings = myFixture.completeBasic().orEmpty().map { it.lookupString }.toSet()
        val documentText = myFixture.editor.document.text

        assertTrue(
            "path completion must offer or insert relative Typst modules, got $lookupStrings in `$documentText`",
            "lib.typ" in lookupStrings || documentText.contains("\"lib.typ\""),
        )
        assertFalse("import path completion must not offer non-Typst files", "notes.txt" in lookupStrings)
    }

    fun testNamedArgumentCompletionOffersBuiltinParameters() {
        myFixture.configureByText("test.typ", "#table(co<caret>)\n")

        val lookupStrings = myFixture.completeBasic().orEmpty().map { it.lookupString }.toSet()

        assertTrue("named argument completion must offer builtin parameters", "columns:" in lookupStrings)
    }

    fun testDiagnosticsWarnForUnresolvedReferenceLabelAndImport() {
        myFixture.configureByText(
            "test.typ",
            """
                #<weak_warning descr="Unresolved Typst reference">missing</weak_warning>
                See <weak_warning descr="Unresolved Typst label">@missing-label</weak_warning>.
                #import <weak_warning descr="Unresolved Typst import">"missing.typ"</weak_warning>: missing
            """.trimIndent(),
        )

        myFixture.checkHighlighting(false, false, true)
    }

    fun testPackageImportDoesNotWarnAsUnsupportedOrUnresolved() {
        myFixture.configureByText(
            "test.typ",
            """
                #import "@preview/cetz:0.3.4": canvas
                #canvas
            """.trimIndent(),
        )

        myFixture.checkHighlighting(false, false, true)
    }

    fun testPackageImportUsageResolvesToImportItemWithoutRegistryLookup() {
        myFixture.configureByText(
            "test.typ",
            """
                #import "@preview/cetz:0.3.4": canvas
                #can<caret>vas
            """.trimIndent(),
        )

        val target = referenceAtCaret()?.resolve()

        assertNotNull("package import usage must resolve to the explicit import item", target)
        assertEquals("canvas", target!!.text)
        assertEquals(myFixture.editor.document.text.indexOf("canvas"), target.textRange.startOffset)
    }

    fun testBuiltinColorInFunctionBodyDoesNotWarnAsUnresolved() {
        myFixture.configureByText(
            "test.typ",
            """
                #let get-level-color(level) = {
                  if level == "danger" { red }
                  else { black }
                }
                #luma(95%)
            """.trimIndent(),
        )

        myFixture.checkHighlighting(false, false, true)
    }

    fun testDiagnosticsWarnForUnresolvedInclude() {
        myFixture.configureByText(
            "test.typ",
            """#include <weak_warning descr="Unresolved Typst include">"missing.typ"</weak_warning>""",
        )

        myFixture.checkHighlighting(false, false, true)
    }

    fun testDiagnosticsErrorForStandaloneBoxExpressionInCodeBlock() {
        myFixture.configureByText(
            "test.typ",
            """
                #{
                  <error descr="Standalone `box` does not produce content; call it with parentheses or a content block">box</error>
                }
            """.trimIndent(),
        )

        myFixture.checkHighlighting(false, false, true)
    }

    fun testDiagnosticsDoNotErrorForBoxUsedAsFunctionValue() {
        myFixture.configureByText("test.typ", "#let make-box = box\n#make-box\n")

        myFixture.checkHighlighting(false, false, true)
    }

    fun testRelativePathDocumentReferenceResolvesAssetFile() {
        myFixture.addFileToProject("assets/logo.png", "not really an image")
        val mainFile = myFixture.addFileToProject("main.typ", "#image(\"assets/lo<caret>go.png\")\n")
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val target = referenceAtCaret()?.resolve()

        assertNotNull("path function string must resolve to the referenced file", target)
        assertEquals("logo.png", target!!.containingFile.name)
    }

    private fun referenceAtCaret() =
        myFixture.file.findReferenceAt(myFixture.caretOffset)

    private fun assertReferenceResolvesTo(text: String, expectedText: String, expectedOffset: Int) {
        myFixture.configureByText("test.typ", text)
        val target = referenceAtCaret()?.resolve()

        assertNotNull("reference must resolve:\n$text", target)
        assertEquals(expectedText, target!!.text)
        assertEquals(expectedOffset, target.textRange.startOffset)
    }
}
