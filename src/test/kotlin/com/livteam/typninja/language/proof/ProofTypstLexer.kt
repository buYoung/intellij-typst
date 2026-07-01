package com.livteam.typninja.language.proof

import com.intellij.lexer.LexerBase
import com.intellij.lexer.RestartableLexer
import com.intellij.lexer.TokenIterator
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.psi.TypstTokenType
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * PROOF-ONLY minimal restartable Typst lexer (run `typst-lang-core-02`, cheap-proof slice).
 *
 * This class lives entirely in the TEST source set and is NOT wired into the plugin. Its only
 * purpose is to empirically de-risk the design spec's restartability strategy:
 *
 *   RestartableLexer + depth-0-only restart point + packed state int, with the mode owned by the
 *   lexer via a bounded resume stack.
 *
 * Contract implemented (see `.agents/.../investigate/design_spec.md`, sections
 * "Restartability strategy" and "Cheap-proof slice"):
 *
 *   - [getState] returns a packed int where 0 == clean top-level markup (empty resume stack AND
 *     logical line-start) and NO other configuration returns 0.
 *   - [getStartState] == 0.
 *   - [isRestartableState] `(s) == (s == 0)`.
 *   - The internal resume [stack] carries the mode nesting (arbitrary depth); it is deliberately
 *     NOT encoded losslessly into the int state, because the platform never restarts mid-group.
 *
 * Scope: Markup(top, restartable) / Markup(nested) / Code. Math is out of scope; `$` is emitted as
 * an opaque single-char [TypstTokenTypes.DOLLAR] token if it appears.
 */
class ProofTypstLexer : LexerBase(), RestartableLexer {

    companion object {
        /** PARBREAK is not present in `TypstTokenTypes`; a local token type for the proof only. */
        @JvmField
        val PARBREAK: IElementType = TypstTokenType("PARBREAK_PROOF")

        // Resume-stack frame kinds. An empty stack == top-level markup.
        private const val F_CONTENT = 0     // '[' .. ']'  -> markup (nested)
        private const val F_PAREN = 1       // '(' .. ')'  in code -> stays code (multi-line)
        private const val F_BRACE = 2       // '{' .. '}'  in code -> stays code (multi-line)
        private const val F_HASH_STMT = 3   // #let / #if ... -> code, ends at line end
        private const val F_HASH_ATOMIC = 4 // #ident / #literal -> code, ends at whitespace

        /** `#`-introduced words that open a statement/block (code runs past whitespace). */
        private val STMT_KEYWORDS = setOf(
            "let", "set", "show", "context", "if", "for", "while",
            "import", "include", "return", "break", "continue",
        )

        private val KEYWORDS: Map<String, IElementType> = mapOf(
            "let" to TypstTokenTypes.KW_LET,
            "set" to TypstTokenTypes.KW_SET,
            "show" to TypstTokenTypes.KW_SHOW,
            "context" to TypstTokenTypes.KW_CONTEXT,
            "if" to TypstTokenTypes.KW_IF,
            "else" to TypstTokenTypes.KW_ELSE,
            "for" to TypstTokenTypes.KW_FOR,
            "while" to TypstTokenTypes.KW_WHILE,
            "in" to TypstTokenTypes.KW_IN,
            "and" to TypstTokenTypes.KW_AND,
            "or" to TypstTokenTypes.KW_OR,
            "not" to TypstTokenTypes.KW_NOT,
            "return" to TypstTokenTypes.KW_RETURN,
            "import" to TypstTokenTypes.KW_IMPORT,
            "include" to TypstTokenTypes.KW_INCLUDE,
            "as" to TypstTokenTypes.KW_AS,
            "break" to TypstTokenTypes.KW_BREAK,
            "continue" to TypstTokenTypes.KW_CONTINUE,
            "true" to TypstTokenTypes.TRUE,
            "false" to TypstTokenTypes.FALSE,
            "none" to TypstTokenTypes.NONE,
            "auto" to TypstTokenTypes.AUTO,
        )
    }

    private var buffer: CharSequence = ""
    private var bufferEndOffset = 0
    private var position = 0

    private var currentTokenType: IElementType? = null
    private var currentTokenStart = 0
    private var currentTokenEnd = 0
    private var stateAtTokenStart = 0

    /** Bounded resume stack. Empty == top-level markup. Never encoded losslessly into the int state. */
    private val stack = ArrayList<Int>()

    /** True when we are at the beginning of a logical line in markup (only leading whitespace seen). */
    private var lineStart = true

    // ---- RestartableLexer ----

    override fun getStartState(): Int = 0

    override fun isRestartableState(state: Int): Boolean = state == 0

    override fun start(
        buf: CharSequence,
        startOffset: Int,
        endOffset: Int,
        initialState: Int,
        tokenIterator: TokenIterator?,
    ) {
        start(buf, startOffset, endOffset, initialState)
    }

    // ---- Lexer / LexerBase ----

    override fun start(buf: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buffer = buf
        bufferEndOffset = endOffset
        position = startOffset
        restoreState(initialState)
        advance()
    }

    override fun getState(): Int = stateAtTokenStart

    override fun getTokenType(): IElementType? = currentTokenType

    override fun getTokenStart(): Int = currentTokenStart

    override fun getTokenEnd(): Int = currentTokenEnd

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = bufferEndOffset

    override fun advance() {
        currentTokenStart = position
        stateAtTokenStart = packState()
        if (position >= bufferEndOffset) {
            currentTokenType = null
            return
        }
        scanToken()
        position = currentTokenEnd
    }

    // ---- internals ----

    private fun restoreState(state: Int) {
        // The platform (and LexerTestCase.checkCorrectRestart) only ever restart from a restartable
        // state, which by contract is 0 == clean top-level markup, empty stack, line-start.
        stack.clear()
        lineStart = true
    }

    /**
     * Packs the current lexer configuration into the int returned by [getState].
     *
     * Invariant: returns 0 IFF (empty resume stack AND line-start) — i.e. clean top-level markup.
     * Every other configuration returns a non-zero value (bit 0 is always set), so no nested /
     * mid-group / code position can ever be mistaken for a restartable state.
     */
    private fun packState(): Int {
        val depth = stack.size
        if (depth == 0 && lineStart) return 0
        val top = if (depth == 0) 0 else (stack[depth - 1] + 1)
        var s = 1 // base bit guarantees a non-zero result for every non-clean configuration
        if (lineStart) s = s or (1 shl 1)
        s = s or ((top and 0x7) shl 2)
        s = s or ((depth and 0x3FFFF) shl 5)
        return s
    }

    private fun inMarkup(): Boolean {
        if (stack.isEmpty()) return true
        return stack[stack.size - 1] == F_CONTENT
    }

    private fun topIsContent(): Boolean =
        stack.isNotEmpty() && stack[stack.size - 1] == F_CONTENT

    private fun scanToken() {
        val c = buffer[position]
        when {
            isSpace(c) -> scanWhitespace()
            inMarkup() -> scanMarkup(c)
            else -> scanCode(c)
        }
    }

    private fun scanWhitespace() {
        var end = position
        var newlines = 0
        while (end < bufferEndOffset && isSpace(buffer[end])) {
            if (buffer[end] == '\n') newlines++
            end++
        }
        // In code, whitespace can terminate an atomic (#ident) or statement (#let ...) hash context.
        if (!inMarkup()) {
            when (stack[stack.size - 1]) {
                F_HASH_ATOMIC -> stack.removeAt(stack.size - 1) // atomic ends at ANY whitespace
                F_HASH_STMT -> if (newlines > 0) stack.removeAt(stack.size - 1) // statement ends at line end
            }
        }
        currentTokenType = if (inMarkup() && newlines >= 2) PARBREAK else TokenType.WHITE_SPACE
        currentTokenEnd = end
        if (newlines > 0) lineStart = true // a newline puts us at a new line's start; spaces-only keep it
    }

    private fun scanMarkup(c: Char) {
        when {
            c == '#' -> {
                currentTokenType = TypstTokenTypes.HASH
                currentTokenEnd = position + 1
                val word = peekWord(position + 1)
                stack.add(if (word in STMT_KEYWORDS) F_HASH_STMT else F_HASH_ATOMIC)
                lineStart = false
            }
            c == '[' -> {
                currentTokenType = TypstTokenTypes.LBRACKET
                currentTokenEnd = position + 1
                stack.add(F_CONTENT)
                lineStart = false
            }
            c == ']' && topIsContent() -> {
                currentTokenType = TypstTokenTypes.RBRACKET
                currentTokenEnd = position + 1
                stack.removeAt(stack.size - 1)
                lineStart = false
            }
            c == '$' -> {
                currentTokenType = TypstTokenTypes.DOLLAR
                currentTokenEnd = position + 1
                lineStart = false
            }
            else -> {
                var end = position
                while (end < bufferEndOffset) {
                    val ch = buffer[end]
                    if (isSpace(ch) || ch == '#' || ch == '[' || ch == '$') break
                    if (ch == ']' && topIsContent()) break
                    end++
                }
                if (end == position) end = position + 1 // guarantee forward progress
                currentTokenType = TypstTokenTypes.TEXT
                currentTokenEnd = end
                lineStart = false
            }
        }
    }

    private fun scanCode(c: Char) {
        when {
            c == '(' -> { single(TypstTokenTypes.LPAREN); stack.add(F_PAREN) }
            c == ')' -> { single(TypstTokenTypes.RPAREN); popIfTop(F_PAREN) }
            c == '{' -> { single(TypstTokenTypes.LBRACE); stack.add(F_BRACE) }
            c == '}' -> { single(TypstTokenTypes.RBRACE); popIfTop(F_BRACE) }
            c == '[' -> { single(TypstTokenTypes.LBRACKET); stack.add(F_CONTENT) }
            c == ']' -> { single(TypstTokenTypes.RBRACKET); popIfTop(F_CONTENT) }
            c == '"' -> scanString()
            c == '.' -> single(TypstTokenTypes.DOT)
            c == ',' -> single(TypstTokenTypes.COMMA)
            c == '=' -> single(TypstTokenTypes.EQ)
            c == '+' -> single(TypstTokenTypes.PLUS)
            c == '-' -> single(TypstTokenTypes.MINUS)
            c == '*' -> single(TypstTokenTypes.STAR)
            c == '/' -> single(TypstTokenTypes.SLASH)
            c == ':' -> single(TypstTokenTypes.COLON)
            c == ';' -> single(TypstTokenTypes.SEMICOLON)
            isDigit(c) -> scanInteger()
            isIdentStart(c) -> scanIdentifier()
            else -> single(TokenType.BAD_CHARACTER) // bounded single-char fallback; never TEXT in code
        }
    }

    private fun single(type: IElementType) {
        currentTokenType = type
        currentTokenEnd = position + 1
    }

    private fun popIfTop(kind: Int) {
        if (stack.isNotEmpty() && stack[stack.size - 1] == kind) stack.removeAt(stack.size - 1)
    }

    private fun scanString() {
        var end = position + 1
        while (end < bufferEndOffset && buffer[end] != '"' && buffer[end] != '\n') end++
        if (end < bufferEndOffset && buffer[end] == '"') end++ // include the closing quote when present
        currentTokenType = TypstTokenTypes.STRING
        currentTokenEnd = end
    }

    private fun scanInteger() {
        var end = position
        while (end < bufferEndOffset && isDigit(buffer[end])) end++
        currentTokenType = TypstTokenTypes.INTEGER_LITERAL
        currentTokenEnd = end
    }

    private fun scanIdentifier() {
        var end = position
        while (end < bufferEndOffset && isIdentPart(buffer[end])) end++
        val word = buffer.subSequence(position, end).toString()
        currentTokenType = KEYWORDS[word] ?: TypstTokenTypes.IDENTIFIER
        currentTokenEnd = end
    }

    private fun peekWord(from: Int): String {
        if (from >= bufferEndOffset || !isIdentStart(buffer[from])) return ""
        var end = from
        while (end < bufferEndOffset && isIdentPart(buffer[end])) end++
        return buffer.subSequence(from, end).toString()
    }

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    private fun isIdentStart(c: Char): Boolean = c == '_' || Character.isLetter(c)

    private fun isIdentPart(c: Char): Boolean = c == '_' || Character.isLetterOrDigit(c)
}
