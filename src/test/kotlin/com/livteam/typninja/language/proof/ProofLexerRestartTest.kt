package com.livteam.typninja.language.proof

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.LexerTestCase
import com.livteam.typninja.language.psi.TypstTokenTypes
import java.io.File

/**
 * Cheap-proof go/no-go tests for run `typst-lang-core-02` (design spec section "Cheap-proof slice").
 *
 * Drives [ProofTypstLexer] over the 6-snippet corpus and empirically proves:
 *   - T1: `LexerTestCase.checkCorrectRestart` passes for each snippet.
 *   - T2: restarting from every restartable token boundary reproduces the forward token stream.
 *   - T3: mid-group offsets are NON-restartable (the platform will never restart there).
 *   - T4: code-region tokens are CODE tokens, never TEXT (the regression that caused "Unexpected token").
 *
 * All checks run against the isolated proof lexer only; nothing here touches production code.
 */
class ProofLexerRestartTest : LexerTestCase() {

    override fun createLexer(): Lexer = ProofTypstLexer()

    override fun getDirPath(): String = ""

    private data class Tok(val type: IElementType, val start: Int, val end: Int, val state: Int)

    private val userSnippet = "#let prefix = if \"prefix\" in data { data.prefix } else { \"\" }"
    private val multilineArray = "#let xs = (\n  1,\n  2,\n)"
    private val multilineCodeBlock = "#{\n  let x = 1\n  x + 2\n}"
    private val contentArg = "#emph[hello] and plain text"
    private val nestedContent = "#box[outer [inner] tail]"
    private val paragraphBoundary = "para one\n\npara two #let a = 1"

    private val corpus: List<Pair<String, String>> = listOf(
        "userSnippet" to userSnippet,
        "multilineArray" to multilineArray,
        "multilineCodeBlock" to multilineCodeBlock,
        "contentArg" to contentArg,
        "nestedContent" to nestedContent,
        "paragraphBoundary" to paragraphBoundary,
    )

    private fun lexAll(text: String): List<Tok> {
        val lexer = ProofTypstLexer()
        lexer.start(text, 0, text.length, 0)
        val out = ArrayList<Tok>()
        while (lexer.tokenType != null) {
            out.add(Tok(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd, lexer.state))
            lexer.advance()
        }
        return out
    }

    private fun escaped(s: String): String = s.replace("\n", "\\n").replace("\t", "\\t")

    private fun dump(text: String): String {
        val sb = StringBuilder()
        for (t in lexAll(text)) {
            sb.append(t.type.toString())
                .append("  '").append(escaped(text.substring(t.start, t.end))).append("'")
                .append("  [").append(t.start).append("..").append(t.end).append(")")
                .append("  state=").append(t.state)
                .append("  restartable=").append(t.state == 0)
                .append('\n')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // T1: LexerTestCase.checkCorrectRestart passes for every corpus snippet.
    // ------------------------------------------------------------------

    fun testT1CheckCorrectRestartUserSnippet() = checkCorrectRestart(userSnippet)

    fun testT1CheckCorrectRestartMultilineArray() = checkCorrectRestart(multilineArray)

    fun testT1CheckCorrectRestartMultilineCodeBlock() = checkCorrectRestart(multilineCodeBlock)

    fun testT1CheckCorrectRestartContentArg() = checkCorrectRestart(contentArg)

    fun testT1CheckCorrectRestartNestedContent() = checkCorrectRestart(nestedContent)

    fun testT1CheckCorrectRestartParagraphBoundary() = checkCorrectRestart(paragraphBoundary)

    // ------------------------------------------------------------------
    // T2: every restartable boundary reproduces the forward token stream exactly.
    // ------------------------------------------------------------------

    fun testT2RestartFromEveryRestartablePointReproducesForwardStream() {
        val probe = ProofTypstLexer()
        for ((name, text) in corpus) {
            val full = lexAll(text)
            var restartablePoints = 0
            for (index in full.indices) {
                val boundary = full[index]
                if (!probe.isRestartableState(boundary.state)) continue
                restartablePoints++

                val restarted = ProofTypstLexer()
                restarted.start(text, boundary.start, text.length, boundary.state)
                val restartedTokens = ArrayList<Tok>()
                while (restarted.tokenType != null) {
                    restartedTokens.add(
                        Tok(restarted.tokenType!!, restarted.tokenStart, restarted.tokenEnd, restarted.state),
                    )
                    restarted.advance()
                }

                val expectedSuffix = full.subList(index, full.size)
                assertEquals(
                    "[$name] restart from offset ${boundary.start} must reproduce the forward stream",
                    expectedSuffix.joinToString("\n") { "${it.type} @${it.start}..${it.end} state=${it.state}" },
                    restartedTokens.joinToString("\n") { "${it.type} @${it.start}..${it.end} state=${it.state}" },
                )
            }
            assertTrue("[$name] must expose at least one restartable point", restartablePoints >= 1)
        }
    }

    // ------------------------------------------------------------------
    // T3: mid-group offsets are NON-restartable (root cause of the reproduced bug is blocked).
    // ------------------------------------------------------------------

    fun testT3MidGroupIsNotRestartable() {
        val probe = ProofTypstLexer()

        // Corpus 2: the `1` inside the multi-line array `( ... )` — a classic mid-group offset.
        val arrayTokens = lexAll(multilineArray)
        val oneInsideArray = arrayTokens.first {
            it.type == TypstTokenTypes.INTEGER_LITERAL && multilineArray.substring(it.start, it.end) == "1"
        }
        assertFalse(
            "mid-array offset before `1,` must NOT be restartable",
            probe.isRestartableState(oneInsideArray.state),
        )
        // The closing `)` of the multi-line group must also be non-restartable.
        val closingParen = arrayTokens.first { it.type == TypstTokenTypes.RPAREN }
        assertFalse("closing `)` of multi-line array must NOT be restartable", probe.isRestartableState(closingParen.state))
        // Only the leading `#` (offset 0, top-level line-start) is restartable in this snippet.
        assertEquals(
            "multi-line array must expose exactly one restartable point (the top-level start)",
            1,
            arrayTokens.count { probe.isRestartableState(it.state) },
        )
        assertEquals(0, arrayTokens.first { probe.isRestartableState(it.state) }.start)

        // Corpus 3: a token inside the multi-line code block `{ ... }`.
        val blockTokens = lexAll(multilineCodeBlock)
        val letInsideBlock = blockTokens.first { it.type == TypstTokenTypes.KW_LET }
        assertFalse(
            "offset inside multi-line code block must NOT be restartable",
            probe.isRestartableState(letInsideBlock.state),
        )
        assertEquals(
            "multi-line code block must expose exactly one restartable point",
            1,
            blockTokens.count { probe.isRestartableState(it.state) },
        )
    }

    // ------------------------------------------------------------------
    // T4: code-region tokens are CODE tokens, never TEXT (kills the "Unexpected token" regression).
    // ------------------------------------------------------------------

    fun testT4CodeRegionTokensAreNeverText() {
        val tokens = lexAll(userSnippet)
        val types = tokens.map { it.type }

        assertTrue(
            "the entire code statement must contain NO TEXT (markup) leakage",
            tokens.none { it.type == TypstTokenTypes.TEXT },
        )
        assertTrue("no BAD_CHARACTER in a well-formed code statement", tokens.none { it.type == TokenType.BAD_CHARACTER })

        assertTrue("`let` must be KW_LET", TypstTokenTypes.KW_LET in types)
        assertTrue("`if` must be KW_IF", TypstTokenTypes.KW_IF in types)
        assertTrue("`in` must be KW_IN", TypstTokenTypes.KW_IN in types)
        assertTrue("`else` must be KW_ELSE", TypstTokenTypes.KW_ELSE in types)
        assertEquals("both `{` must be LBRACE", 2, tokens.count { it.type == TypstTokenTypes.LBRACE })
        assertEquals("both closing `}` must be RBRACE (not TEXT)", 2, tokens.count { it.type == TypstTokenTypes.RBRACE })
        assertEquals("both strings must be STRING", 2, tokens.count { it.type == TypstTokenTypes.STRING })
        assertTrue("`.` must be DOT", TypstTokenTypes.DOT in types)
        assertTrue("`=` must be EQ", TypstTokenTypes.EQ in types)
        assertTrue("identifiers must be IDENTIFIER", TypstTokenTypes.IDENTIFIER in types)

        // Corpus 2 cross-check: the reproduced-bug tokens `)` and the integers must be code, not TEXT.
        val arrayTokens = lexAll(multilineArray)
        assertTrue("multi-line array `)` must be RPAREN", arrayTokens.any { it.type == TypstTokenTypes.RPAREN })
        assertTrue("multi-line array must have no TEXT leakage", arrayTokens.none { it.type == TypstTokenTypes.TEXT })
        assertEquals(
            "both integers inside the array must be INTEGER_LITERAL",
            2,
            arrayTokens.count { it.type == TypstTokenTypes.INTEGER_LITERAL },
        )

        writeEvidence()
    }

    /** Writes the full token dumps as a data artifact for the go/no-go report. */
    private fun writeEvidence() {
        val sb = StringBuilder()
        sb.append("ProofTypstLexer token dumps (run typst-lang-core-02, cheap-proof slice)\n")
        sb.append("=".repeat(72)).append("\n\n")
        for ((name, text) in corpus) {
            sb.append("### ").append(name).append("  |  '").append(escaped(text)).append("'\n")
            sb.append(dump(text)).append("\n")
        }
        val target = File(System.getProperty("user.dir"))
            .resolve(".agents/orchestration/typst-lang-core-02/proof/token_dump.txt")
        target.parentFile.mkdirs()
        target.writeText(sb.toString())
    }
}
