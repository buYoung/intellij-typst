package com.livteam.typninja.language.lexer

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.LexerTestCase
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * Production restart-contract tests for [TypstLexer] (the promoted proof lexer).
 *
 * Locks the HARD requirement from the design spec ("Restartability strategy"): the lexer passes
 * `LexerTestCase.checkCorrectRestart` on a corpus that includes the reported user snippet plus
 * multi-line arrays / code blocks / content / markup / math, and mid-group offsets are never
 * restartable (so the incremental-relex bug that mis-classified code as markup cannot recur).
 */
class TypstLexerRestartTest : LexerTestCase() {

    override fun createLexer(): Lexer = TypstLexer()

    override fun getDirPath(): String = ""

    private data class Tok(val type: IElementType, val start: Int, val end: Int, val state: Int)

    // --- corpus (design "Cheap-proof slice" + markers / math added for P2) ---
    private val userSnippet = "#let prefix = if \"prefix\" in data { data.prefix } else { \"\" }"
    private val multilineArray = "#let xs = (\n  1,\n  2,\n)"
    private val multilineCodeBlock = "#{\n  let x = 1\n  x + 2\n}"
    private val contentArg = "#emph[hello] and plain text"
    private val nestedContent = "#box[outer [inner] tail]"
    private val paragraphBoundary = "para one\n\npara two #let a = 1"
    private val headingAndList = "= Title\n\n- one\n- two\n\n+ first\n/ term: def"
    private val mathRegion = "text $ a + b^2 $ tail"
    private val refAndLabel = "See @intro and <target> here"
    private val measurements = "#set text(size: 12pt, ratio: 50%)"

    private val corpus: List<Pair<String, String>> = listOf(
        "userSnippet" to userSnippet,
        "multilineArray" to multilineArray,
        "multilineCodeBlock" to multilineCodeBlock,
        "contentArg" to contentArg,
        "nestedContent" to nestedContent,
        "paragraphBoundary" to paragraphBoundary,
        "headingAndList" to headingAndList,
        "mathRegion" to mathRegion,
        "refAndLabel" to refAndLabel,
        "measurements" to measurements,
    )

    private fun lexAll(text: String): List<Tok> {
        val lexer = TypstLexer()
        lexer.start(text, 0, text.length, 0)
        val out = ArrayList<Tok>()
        while (lexer.tokenType != null) {
            out.add(Tok(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd, lexer.state))
            lexer.advance()
        }
        return out
    }

    // ------------------------------------------------------------------
    // checkCorrectRestart on every corpus snippet.
    // ------------------------------------------------------------------

    fun testCheckCorrectRestartUserSnippet() = checkCorrectRestart(userSnippet)
    fun testCheckCorrectRestartMultilineArray() = checkCorrectRestart(multilineArray)
    fun testCheckCorrectRestartMultilineCodeBlock() = checkCorrectRestart(multilineCodeBlock)
    fun testCheckCorrectRestartContentArg() = checkCorrectRestart(contentArg)
    fun testCheckCorrectRestartNestedContent() = checkCorrectRestart(nestedContent)
    fun testCheckCorrectRestartParagraphBoundary() = checkCorrectRestart(paragraphBoundary)
    fun testCheckCorrectRestartHeadingAndList() = checkCorrectRestart(headingAndList)
    fun testCheckCorrectRestartMathRegion() = checkCorrectRestart(mathRegion)
    fun testCheckCorrectRestartRefAndLabel() = checkCorrectRestart(refAndLabel)
    fun testCheckCorrectRestartMeasurements() = checkCorrectRestart(measurements)

    // ------------------------------------------------------------------
    // Every restartable boundary reproduces the forward token stream exactly.
    // ------------------------------------------------------------------

    fun testRestartFromEveryRestartablePointReproducesForwardStream() {
        val probe = TypstLexer()
        for ((name, text) in corpus) {
            val full = lexAll(text)
            var restartablePoints = 0
            for (index in full.indices) {
                val boundary = full[index]
                if (!probe.isRestartableState(boundary.state)) continue
                restartablePoints++

                val restarted = TypstLexer()
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
    // Mid-group offsets are NON-restartable (root cause of the reported bug is structurally blocked).
    // ------------------------------------------------------------------

    fun testMidGroupIsNotRestartable() {
        val probe = TypstLexer()

        val arrayTokens = lexAll(multilineArray)
        val oneInsideArray = arrayTokens.first {
            it.type == TypstTokenTypes.INTEGER_LITERAL && multilineArray.substring(it.start, it.end) == "1"
        }
        assertFalse("mid-array offset before `1,` must NOT be restartable", probe.isRestartableState(oneInsideArray.state))
        val closingParen = arrayTokens.first { it.type == TypstTokenTypes.RPAREN }
        assertFalse("closing `)` of a multi-line array must NOT be restartable", probe.isRestartableState(closingParen.state))
        assertEquals(
            "multi-line array must expose exactly one restartable point (the top-level start)",
            1,
            arrayTokens.count { probe.isRestartableState(it.state) },
        )
        assertEquals(0, arrayTokens.first { probe.isRestartableState(it.state) }.start)

        val blockTokens = lexAll(multilineCodeBlock)
        val letInsideBlock = blockTokens.first { it.type == TypstTokenTypes.KW_LET }
        assertFalse("offset inside a multi-line code block must NOT be restartable", probe.isRestartableState(letInsideBlock.state))
        assertEquals(1, blockTokens.count { probe.isRestartableState(it.state) })
    }

    // ------------------------------------------------------------------
    // Code-region tokens are CODE tokens, never TEXT (kills the "Unexpected token" regression).
    // ------------------------------------------------------------------

    fun testCodeRegionTokensAreNeverText() {
        val tokens = lexAll(userSnippet)
        val types = tokens.map { it.type }

        assertTrue("the code statement must contain NO TEXT leakage", tokens.none { it.type == TypstTokenTypes.TEXT })
        assertTrue("no BAD_CHARACTER in a well-formed code statement", tokens.none { it.type == TokenType.BAD_CHARACTER })
        assertTrue("`let` must be KW_LET", TypstTokenTypes.KW_LET in types)
        assertTrue("`if` must be KW_IF", TypstTokenTypes.KW_IF in types)
        assertTrue("`in` must be KW_IN", TypstTokenTypes.KW_IN in types)
        assertTrue("`else` must be KW_ELSE", TypstTokenTypes.KW_ELSE in types)
        assertEquals("both `{` must be LBRACE", 2, tokens.count { it.type == TypstTokenTypes.LBRACE })
        assertEquals("both closing `}` must be RBRACE (not TEXT)", 2, tokens.count { it.type == TypstTokenTypes.RBRACE })
        assertEquals("both strings must be STRING", 2, tokens.count { it.type == TypstTokenTypes.STRING })

        val arrayTokens = lexAll(multilineArray)
        assertTrue("multi-line array `)` must be RPAREN", arrayTokens.any { it.type == TypstTokenTypes.RPAREN })
        assertTrue("multi-line array must have no TEXT leakage", arrayTokens.none { it.type == TypstTokenTypes.TEXT })
        assertEquals("both integers inside the array must be INTEGER_LITERAL", 2, arrayTokens.count { it.type == TypstTokenTypes.INTEGER_LITERAL })
    }

    // ------------------------------------------------------------------
    // The P2 token model is actually emitted (markers / shorthand / ref / label / numeric / math).
    // ------------------------------------------------------------------

    fun testNewTokenModelIsEmitted() {
        assertTrue("`= ` must be a HEADING_MARKER", lexAll(headingAndList).any { it.type == TypstTokenTypes.HEADING_MARKER })
        assertTrue("`- ` must be a LIST_MARKER", lexAll(headingAndList).any { it.type == TypstTokenTypes.LIST_MARKER })
        assertTrue("`+ ` must be an ENUM_MARKER", lexAll(headingAndList).any { it.type == TypstTokenTypes.ENUM_MARKER })
        assertTrue("`/ ` must be a TERM_MARKER", lexAll(headingAndList).any { it.type == TypstTokenTypes.TERM_MARKER })

        val refTokens = lexAll(refAndLabel)
        assertTrue("`@intro` must be a single REF_MARKER", refTokens.any { it.type == TypstTokenTypes.REF_MARKER && refAndLabel.substring(it.start, it.end) == "@intro" })
        assertTrue("`<target>` must be a LABEL_DEF", refTokens.any { it.type == TypstTokenTypes.LABEL_DEF && refAndLabel.substring(it.start, it.end) == "<target>" })

        val measureTokens = lexAll(measurements)
        assertTrue("`12pt` must be NUMERIC_LITERAL", measureTokens.any { it.type == TypstTokenTypes.NUMERIC_LITERAL && measurements.substring(it.start, it.end) == "12pt" })
        assertTrue("`50%` must be NUMERIC_LITERAL", measureTokens.any { it.type == TypstTokenTypes.NUMERIC_LITERAL && measurements.substring(it.start, it.end) == "50%" })

        val mathTokens = lexAll(mathRegion)
        assertTrue("math must open/close with DOLLAR", mathTokens.count { it.type == TypstTokenTypes.DOLLAR } == 2)
        assertTrue("math body must produce a MATH_IDENT", mathTokens.any { it.type == TypstTokenTypes.MATH_IDENT })
    }

    // ------------------------------------------------------------------
    // Regressions for the reported bugs (leading-dot numbers + atomic `#` boundary).
    // ------------------------------------------------------------------

    fun testLeadingDotIsASingleNumericLiteral() {
        val em = lexAll("#f(x: .2em)")
        assertTrue(
            "`.2em` must be one NUMERIC_LITERAL, not DOT + number",
            em.any { it.type == TypstTokenTypes.NUMERIC_LITERAL && "#f(x: .2em)".substring(it.start, it.end) == ".2em" },
        )
        assertTrue("`.5` must be a single FLOAT_LITERAL", lexAll("#let o = .5").any {
            it.type == TypstTokenTypes.FLOAT_LITERAL && "#let o = .5".substring(it.start, it.end) == ".5"
        })
    }

    fun testAtomicHashEndsAtMarkupPunctuation() {
        val text = "See #a, #b! and #c."
        val tokens = lexAll(text)
        // `#a`, `#b`, `#c` are atomic; the following `,` `!` `.` are markup TEXT, never code punctuation.
        assertTrue("no code COMMA may leak from an atomic #var", tokens.none { it.type == TypstTokenTypes.COMMA })
        assertEquals("three `#` sigils", 3, tokens.count { it.type == TypstTokenTypes.HASH })
        assertEquals("three inline identifiers", 3, tokens.count { it.type == TypstTokenTypes.IDENTIFIER })
        // Chained tails must stay code: `#f(x).method(1)` keeps its dot/paren tail.
        val chained = lexAll("#f(x).method(1) tail")
        assertEquals("chained call keeps both parens as code", 2, chained.count { it.type == TypstTokenTypes.LPAREN })
        assertTrue("chained call keeps the field-access DOT as code", chained.any { it.type == TypstTokenTypes.DOT })
    }

    // Restart contract must still hold for the new boundary logic.
    fun testCheckCorrectRestartLeadingDot() = checkCorrectRestart("#show: f.with(fill: luma(95%), radius: .2em)\n")
    fun testCheckCorrectRestartInlineVarPunctuation() = checkCorrectRestart("The values are #a, #b, and #c.\n")
    fun testCheckCorrectRestartInlineIfElse() = checkCorrectRestart("#if draft [a] else [b] and prose.\n")

    // ------------------------------------------------------------------
    // M2: markup emphasis toggles honor typst's word-boundary rule (`!in_word()`).
    // ------------------------------------------------------------------

    fun testWordInternalMarkersAreLiteralText() {
        // Inside a word (both neighbours are word chars) `*` / `_` are TEXT, never toggles.
        val snake = lexAll("test_data_set here")
        assertTrue("word-internal `_` must not lex as an UNDERSCORE toggle",
            snake.none { it.type == TypstTokenTypes.UNDERSCORE })
        val product = lexAll("2*3*4 done")
        assertTrue("word-internal `*` must not lex as a STAR toggle",
            product.none { it.type == TypstTokenTypes.STAR })
        val mixed = lexAll("a_b and c*d")
        assertTrue("single word-internal markers stay literal",
            mixed.none { it.type == TypstTokenTypes.UNDERSCORE || it.type == TypstTokenTypes.STAR })
    }

    fun testRealEmphasisMarkersStillToggle() {
        // At a word boundary (a neighbour is not a word char) the markers still toggle.
        assertEquals("`_bold_` must emit two UNDERSCORE toggles", 2,
            lexAll("_bold_ text").count { it.type == TypstTokenTypes.UNDERSCORE })
        assertEquals("`*strong*` must emit two STAR toggles", 2,
            lexAll("*strong* text").count { it.type == TypstTokenTypes.STAR })
        // A marker touching only punctuation/space on one side is still a toggle.
        assertTrue("`(*strong*)` opens with a STAR toggle",
            lexAll("(*strong*)").any { it.type == TypstTokenTypes.STAR })
    }

    // Restart contract must still hold with the word-boundary emphasis classification.
    fun testCheckCorrectRestartWordBoundaryEmphasis() =
        checkCorrectRestart("Use test_data_set and 2*3\n\n_emph_ then *strong* end\n")
}
