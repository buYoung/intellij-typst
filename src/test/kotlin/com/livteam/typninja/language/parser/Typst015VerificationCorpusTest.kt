package com.livteam.typninja.language.parser

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.livteam.typninja.language.analysis.TypstDiagnosticEngine
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstImportItem
import com.livteam.typninja.language.psi.TypstMathIdentifier
import com.livteam.typninja.language.psi.TypstModuleImport
import com.livteam.typninja.language.psi.TypstRef
import com.livteam.typninja.language.psi.TypstReferenceExpression
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** End-to-end acceptance tests for the executable Typst 0.15 verification corpus. */
class Typst015VerificationCorpusTest : BasePlatformTestCase() {

    private val repositoryRoot: Path
        get() = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

    private val samplesRoot: Path
        get() = repositoryRoot.resolve("samples")

    private fun validCorpusFiles(): List<Path> = Files.walk(samplesRoot.resolve("verify")).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".typ") }
            .sorted()
            .toList()
    } + listOf(samplesRoot.resolve("verify.typ"))

    fun testValidCorpusHasNoParserErrorsBadCharactersAndCoversEveryFile() {
        val failures = ArrayList<String>()
        validCorpusFiles().forEachIndexed { index, path ->
            val text = Files.readString(path)
            val file = myFixture.configureByText("corpus-$index.typ", text) as TypstFile
            val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
            val badCharacters = PsiTreeUtil.collectElements(file) { it.node.elementType == TokenType.BAD_CHARACTER }
            if (errors.isNotEmpty()) {
                failures.add("${samplesRoot.relativize(path)}: ${errors.joinToString { it.errorDescription }}")
            }
            if (badCharacters.isNotEmpty()) failures.add("${samplesRoot.relativize(path)}: BAD_CHARACTER")
            if (file.text != text) failures.add("${samplesRoot.relativize(path)}: PSI tree does not cover the file")
        }
        assertTrue("valid Typst 0.15 corpus failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    fun testCorpusProducesEveryMajorSyntaxNode() {
        val observed = HashSet<com.intellij.psi.tree.IElementType>()
        validCorpusFiles().forEachIndexed { index, path ->
            val file = myFixture.configureByText("syntax-$index.typ", Files.readString(path)) as TypstFile
            PsiTreeUtil.processElements(file) { element -> observed.add(element.node.elementType); true }
        }
        val expected = setOf(
            E.MARKUP, E.CODE_EXPRESSION, E.MATH, E.RAW, E.STRING_LITERAL, E.CONTENT_BLOCK, E.CODE_BLOCK,
            E.HEADING, E.LIST_ITEM, E.ENUM_ITEM, E.TERM_ITEM, E.STRONG, E.EMPH, E.REF, E.LABEL,
            E.LET_BINDING, E.SET_RULE, E.SHOW_RULE, E.CONTEXTUAL, E.CONDITIONAL, E.WHILE_LOOP,
            E.FOR_LOOP, E.MODULE_IMPORT, E.IMPORT_ITEMS, E.IMPORT_ITEM, E.IMPORT_GLOB,
            E.MODULE_INCLUDE, E.LOOP_BREAK, E.LOOP_CONTINUE, E.FUNC_RETURN,
            E.REFERENCE_EXPR, E.FUNC_CALL, E.ARGS, E.FIELD_ACCESS, E.CLOSURE, E.PARAMS, E.UNARY, E.BINARY,
            E.ARRAY, E.DICT, E.PARENTHESIZED, E.NAMED, E.KEYED, E.SPREAD, E.DESTRUCTURING,
            E.DESTRUCT_ASSIGNMENT, E.BINDING_DECLARATION, E.MATH_DELIMITED, E.MATH_REFERENCE,
        )
        assertEquals(
            "the corpus must exercise every major Typst 0.15 AST kind",
            emptySet<com.intellij.psi.tree.IElementType>(),
            expected - observed,
        )
    }

    fun testDestructuringAssignmentProducesDedicatedNode() {
        val file = myFixture.configureByText(
            "destructuring-assignment.typ",
            "#let assignments = {\n  let left = 1\n  let right = 2\n  (left, right) = (right, left)\n}",
        ) as TypstFile
        val nodes = PsiTreeUtil.collectElements(file) { it.node.elementType == E.DESTRUCT_ASSIGNMENT }
        assertEquals(
            com.intellij.psi.impl.DebugUtil.psiToString(file, true),
            1,
            nodes.size,
        )
    }

    fun testLocalImportLabelMathAndBuiltinReferencesResolve() {
        val exports = Files.readString(samplesRoot.resolve("verify/modules/exports.typ"))
        myFixture.addFileToProject("verify/modules/exports.typ", exports)
        val ide = myFixture.addFileToProject(
            "verify/06-ide.typ",
            Files.readString(samplesRoot.resolve("verify/06-ide.typ")),
        ) as TypstFile

        val importPath = PsiTreeUtil.findChildrenOfType(ide, TypstModuleImport::class.java).single()
        assertNotNull("relative import path must resolve to exports.typ", importPath.reference?.resolve())

        val importItem = PsiTreeUtil.findChildrenOfType(ide, TypstImportItem::class.java)
            .single { it.sourceName == "exported-add" }
        assertEquals("exported-add", importItem.reference?.resolve()?.text)

        val references = PsiTreeUtil.findChildrenOfType(ide, TypstReferenceExpression::class.java)
        assertNotNull("local symbol must resolve", references.first { it.referenceName == "local-function" }.reference.resolve())
        assertNotNull("imported symbol must resolve", references.first { it.referenceName == "exported-add" }.reference.resolve())
        assertNotNull("builtin symbol must resolve", references.first { it.referenceName == "counter" }.reference.resolve())

        val label = PsiTreeUtil.findChildrenOfType(ide, TypstRef::class.java).single { it.referenceName == "ide-heading" }
        assertNotNull("label reference must resolve", label.reference?.resolve())

        val math = PsiTreeUtil.findChildrenOfType(ide, TypstMathIdentifier::class.java)
            .first { it.qualifiedPath == "arrow.r.long" }
        assertNotNull("qualified math builtin must resolve", math.reference?.resolve())
    }

    fun testValidCorpusProducesNoNativeSemanticDiagnostics() {
        val failures = ArrayList<String>()
        validCorpusFiles().forEachIndexed { index, path ->
            val file = myFixture.configureByText("diagnostics-$index.typ", Files.readString(path)) as TypstFile
            val diagnostics = LinkedHashSet<com.livteam.typninja.language.analysis.TypstNativeDiagnostic>()
            PsiTreeUtil.processElements(file) { element ->
                diagnostics.addAll(TypstDiagnosticEngine.diagnosticsFor(element))
                true
            }
            if (diagnostics.isNotEmpty()) {
                failures.add("${samplesRoot.relativize(path)}: ${diagnostics.joinToString { diagnostic ->
                    val text = file.text.substring(diagnostic.range.startOffset, diagnostic.range.endOffset)
                    "${diagnostic.message} at `$text`"
                }}")
            }
        }
        assertTrue("valid corpus must not produce native semantic diagnostics:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    fun testInvalidCorpusRecoveryErrorsAreExpectedAndDisjoint() {
        val path = samplesRoot.resolve("verify-invalid.typ")
        val file = myFixture.configureByText("verify-invalid.typ", Files.readString(path)) as TypstFile
        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
            .sortedBy { it.textRange.startOffset }
        assertTrue(errors.any { it.errorDescription == "Expected a binding name" })
        assertTrue(errors.any { it.errorDescription == "Unmatched ')'" })
        errors.zipWithNext().forEach { (left, right) ->
            assertTrue("parser recovery ranges must not overlap", left.textRange.endOffset <= right.textRange.startOffset)
        }
        val unresolvedSymbol = PsiTreeUtil.findChildrenOfType(file, TypstReferenceExpression::class.java)
            .single { it.referenceName == "missing-symbol" }
        assertNull("the invalid symbol must remain unresolved", unresolvedSymbol.reference.resolve())
        val unresolvedLabel = PsiTreeUtil.findChildrenOfType(file, TypstRef::class.java)
            .single { it.referenceName == "missing-label" }
        assertNull("the invalid label must remain unresolved", unresolvedLabel.reference?.resolve())
        val unresolvedImport = PsiTreeUtil.findChildrenOfType(file, TypstModuleImport::class.java)
            .single { it.pathString == "missing.typ" }
        assertNull("the invalid import must remain unresolved", unresolvedImport.reference?.resolve())
        val diagnostics = LinkedHashSet<com.livteam.typninja.language.analysis.TypstNativeDiagnostic>()
        PsiTreeUtil.processElements(file) { element ->
            diagnostics.addAll(TypstDiagnosticEngine.diagnosticsFor(element))
            true
        }
        assertTrue(diagnostics.any { it.message == "Unknown named argument `colums`" })
        assertEquals(Files.readString(path), file.text)
    }

    fun testTypstCli0151CompilesMainAndEveryStandaloneModule() {
        val version = runProcess(listOf("typst", "--version"), repositoryRoot)
        assertTrue("Typst CLI 0.15.1 is required, found: ${version.output}",
            version.exitCode == 0 && version.output.contains("typst 0.15.1"))

        val outputDirectory = Files.createTempDirectory("typst-015-corpus")
        val inputs = listOf(samplesRoot.resolve("verify.typ")) +
            Files.list(samplesRoot.resolve("verify")).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().matches(Regex("0\\d-.*\\.typ")) }
                    .sorted()
                    .toList()
            }
        inputs.forEach { input ->
            val output = outputDirectory.resolve(input.fileName.toString().removeSuffix(".typ") + ".pdf")
            val result = runProcess(
                listOf(
                    "typst", "compile", "--package-path", samplesRoot.resolve("verify/packages").toString(),
                    input.toString(), output.toString(),
                ),
                repositoryRoot,
            )
            assertEquals("Typst failed for ${samplesRoot.relativize(input)}:\n${result.output}", 0, result.exitCode)
            assertTrue("Typst did not create $output", Files.isRegularFile(output) && Files.size(output) > 0)
        }
    }

    private fun runProcess(command: List<String>, directory: Path): ProcessResult {
        val process = ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        assertTrue("process timed out: ${command.joinToString(" ")}", process.waitFor(60, TimeUnit.SECONDS))
        return ProcessResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
