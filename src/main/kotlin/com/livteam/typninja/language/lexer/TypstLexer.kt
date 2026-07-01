package com.livteam.typninja.language.lexer

import com.intellij.lexer.LexerBase
import com.intellij.lexer.RestartableLexer
import com.intellij.lexer.TokenIterator
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * Production hand-written, mode-aware, restartable Typst lexer (Typst 0.15.0 baseline).
 *
 * Replaces the JFlex `_TypstLexer` + `FlexAdapter`. It is the promotion of the proven proof lexer
 * (`src/test/.../proof/ProofTypstLexer.kt`, go/no-go PASSED) extended to ALL three modes
 * (Markup top / Markup nested / Code / Math) and the full token model in [TypstTokenTypes].
 *
 * ## Modes
 * The lexer OWNS mode switching (IntelliJ eagerly tokenizes the whole file, so the parser cannot
 * drive re-lexing; see the design spec "Modes & transitions"). A bounded resume [stack] carries the
 * mode nesting at arbitrary depth. The active mode is a pure function of the stack top:
 *   - empty stack           -> Markup (top level)
 *   - top == [F_CONTENT]    -> Markup (nested, inside `[ ... ]`)
 *   - top == code frame     -> Code   ([F_PAREN] / [F_BRACE] / [F_HASH_STMT] / [F_HASH_ATOMIC])
 *   - top == math frame     -> Math   ([F_MATH] / [F_MATH_DELIM])
 *
 * ## Restart contract (HARD requirement — must pass `checkCorrectRestart`)
 *   - [getState] returns a packed int that is `0` IFF the configuration is CLEAN top-level markup
 *     (empty stack AND logical line-start). Every other configuration returns a non-zero int
 *     (bit 0 is always set), so no nested / mid-group / code / math position can be mistaken for a
 *     restartable state.
 *   - [getStartState] == 0, [isRestartableState] `(s) == (s == 0)`.
 *   - The platform only restarts where `state == 0`; [restoreState] there clears the stack and sets
 *     line-start, exactly reproducing the forward-pass configuration, so the forward token stream is
 *     reproduced byte-for-byte. Mid-group offsets are never restarted; the platform back-walks to the
 *     nearest clean point. This structurally eliminates the reported incremental-relex bug (code
 *     mis-classified as markup -> "Unexpected token").
 *
 * Lexers are single-use per run; create a fresh instance at every call site.
 */
class TypstLexer : LexerBase(), RestartableLexer {

    private companion object {
        // Resume-stack frame kinds. An empty stack == top-level markup. Values 0..6 (fit in 3 bits).
        private const val F_CONTENT = 0     // '[' .. ']'      -> markup (nested)
        private const val F_PAREN = 1       // '(' .. ')' code -> stays code (multi-line)
        private const val F_BRACE = 2       // '{' .. '}' code -> stays code (multi-line)
        private const val F_HASH_STMT = 3   // #let / #if ...  -> code, ends at line end
        private const val F_HASH_ATOMIC = 4 // #ident / #lit   -> code, ends at whitespace
        private const val F_MATH = 5        // '$' .. '$'      -> math
        private const val F_MATH_DELIM = 6  // '(' '[' '{' in math -> stays math

        private const val FORM_FEED = '\u000C'

        /** `#`-introduced words that open a statement / block (code runs past whitespace). */
        private val STMT_KEYWORDS = setOf(
            "let", "set", "show", "context", "if", "for", "while",
            "import", "include", "return", "break", "continue", "else",
        )

        /** Length / angle / ratio / fraction units (for `NUMERIC_LITERAL`). */
        private val UNITS = setOf("pt", "mm", "cm", "in", "em", "fr", "deg", "rad")

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

    /** True at the beginning of a logical markup line (only leading whitespace seen). */
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

    // ---- state (restart contract) ----

    /**
     * The platform only ever restarts from a restartable state, which by contract is `0` == clean
     * top-level markup (empty stack, line-start). Any [initialState] is therefore treated as that
     * clean configuration.
     */
    private fun restoreState(@Suppress("UNUSED_PARAMETER") state: Int) {
        stack.clear()
        lineStart = true
    }

    /**
     * Packs the current configuration into the int returned by [getState].
     *
     * Invariant: returns `0` IFF (empty resume stack AND line-start) — i.e. clean top-level markup.
     * Every other configuration returns a non-zero value (bit 0 is always set).
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

    // ---- mode helpers ----

    private fun topFrame(): Int = if (stack.isEmpty()) -1 else stack[stack.size - 1]

    private fun inMarkup(): Boolean {
        val top = topFrame()
        return top == -1 || top == F_CONTENT
    }

    private fun inMath(): Boolean {
        val top = topFrame()
        return top == F_MATH || top == F_MATH_DELIM
    }

    private fun topIsContent(): Boolean = topFrame() == F_CONTENT

    private fun push(kind: Int) = stack.add(kind)

    private fun pop() = stack.removeAt(stack.size - 1)

    private fun popIfTop(kind: Int) {
        if (topFrame() == kind) pop()
    }

    /**
     * A markup-embedded atomic `#`-expression (`#ident`, `#literal`, `#call(...)`, `#x[..]`) continues
     * ONLY into a directly-adjacent tail: `(args)`, `[content]`, or `.field`. Called right after an
     * atom-completing token; if no tail follows, the atomic frame is popped so the next character
     * returns to Markup — this stops `#x`, `#x,`, `#x!`, `#f(...)!` etc. from swallowing following
     * prose/punctuation as code (the reported inline-variable bug). Whitespace already ends the atomic
     * via [scanWhitespace]; this adds the non-whitespace, non-tail case.
     */
    private fun endAtomicIfNoTail() {
        if (topFrame() != F_HASH_ATOMIC) return
        val next = if (currentTokenEnd < bufferEndOffset) buffer[currentTokenEnd] else ' '
        val continuesTail = when (next) {
            '(', '[' -> true
            '.' -> currentTokenEnd + 1 < bufferEndOffset && isIdentStart(buffer[currentTokenEnd + 1])
            else -> false
        }
        if (!continuesTail) pop()
    }

    /**
     * A markup control-flow statement whose branch is a CONTENT block (`#if c [..] else [..]`,
     * `#for x in xs [..]`) ends once a closed `[..]` branch is NOT followed on the same line by
     * `else` or another `[..]`/`{..}` branch — the trailing same-line text is markup, not code. This
     * targets only content-block branches (not `(...)`/`{...}`), so `#let f(a,b) = ...` and
     * `#let x = (1, 2)` are unaffected.
     */
    private fun endStmtAfterContentBlock() {
        if (topFrame() != F_HASH_STMT) return
        var i = currentTokenEnd
        while (i < bufferEndOffset && (buffer[i] == ' ' || buffer[i] == '\t')) i++
        if (i >= bufferEndOffset) return
        val c = buffer[i]
        if (c == '\n' || c == '\r' || c == '[' || c == '{') return // newline / another branch: keep going
        if (isIdentStart(c) && peekWord(i) == "else") return
        pop()
    }

    // ---- scanning ----

    private fun scanToken() {
        val c = buffer[position]
        when {
            isSpace(c) -> scanWhitespace()
            c == '/' && peek(1) == '/' -> scanLineComment()
            c == '/' && peek(1) == '*' -> scanBlockComment()
            c == '`' -> scanRaw()
            inMath() -> scanMath(c)
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
        // Mode-specific frame adjustments happen DURING the whitespace run.
        if (!inMarkup()) {
            when (topFrame()) {
                F_HASH_ATOMIC -> pop() // atomic #-expression ends at ANY whitespace
                F_HASH_STMT -> if (newlines > 0) pop() // statement #-expression ends at line end
                F_MATH, F_MATH_DELIM ->
                    // Recover an unclosed math region at a blank line so it cannot pollute the rest
                    // of the document; pop back out of every math frame.
                    if (newlines >= 2) while (inMath()) pop()
            }
        }
        currentTokenType = if (inMarkup() && newlines >= 2) TypstTokenTypes.PARBREAK else TokenType.WHITE_SPACE
        currentTokenEnd = end
        if (newlines > 0) lineStart = true
    }

    private fun scanLineComment() {
        var end = position + 2
        while (end < bufferEndOffset && buffer[end] != '\n' && buffer[end] != '\r') end++
        currentTokenType = TypstTokenTypes.LINE_COMMENT
        currentTokenEnd = end
        lineStart = false
    }

    /** Nested / unterminated block comment as a SINGLE token (mirrors the old JFlex behaviour). */
    private fun scanBlockComment() {
        var end = position + 2
        var depth = 1
        while (end < bufferEndOffset && depth > 0) {
            if (end + 1 < bufferEndOffset && buffer[end] == '/' && buffer[end + 1] == '*') {
                depth++; end += 2
            } else if (end + 1 < bufferEndOffset && buffer[end] == '*' && buffer[end + 1] == '/') {
                depth--; end += 2
            } else {
                end++
            }
        }
        currentTokenType = TypstTokenTypes.BLOCK_COMMENT
        currentTokenEnd = end
        lineStart = false
    }

    /** Inline `` `...` `` or fenced ```` ```...``` ```` raw text as one token. */
    private fun scanRaw() {
        val fenced = peek(1) == '`' && peek(2) == '`'
        var end: Int
        if (fenced) {
            end = position + 3
            while (end < bufferEndOffset) {
                if (buffer[end] == '`' && end + 1 < bufferEndOffset && buffer[end + 1] == '`' &&
                    end + 2 < bufferEndOffset && buffer[end + 2] == '`'
                ) {
                    end += 3
                    break
                }
                end++
            }
        } else {
            end = position + 1
            while (end < bufferEndOffset && buffer[end] != '`' && buffer[end] != '\n' && buffer[end] != '\r') end++
            if (end < bufferEndOffset && buffer[end] == '`') end++
        }
        currentTokenType = TypstTokenTypes.RAW_TEXT
        currentTokenEnd = end
        lineStart = false
    }

    // ---- markup ----

    private fun scanMarkup(c: Char) {
        // Line-start markers are only markers at the very beginning of a logical line.
        if (lineStart && scanLineStartMarker(c)) return
        when (c) {
            '#' -> {
                emit(TypstTokenTypes.HASH, position + 1)
                val word = peekWord(position + 1)
                push(if (word in STMT_KEYWORDS) F_HASH_STMT else F_HASH_ATOMIC)
            }
            '[' -> { emit(TypstTokenTypes.LBRACKET, position + 1); push(F_CONTENT) }
            ']' -> if (topIsContent()) {
                emit(TypstTokenTypes.RBRACKET, position + 1); pop(); endAtomicIfNoTail(); endStmtAfterContentBlock()
            } else scanText()
            '$' -> { emit(TypstTokenTypes.DOLLAR, position + 1); push(F_MATH) }
            // Emphasis toggles only when NOT inside a word (typst's `!in_word()`): a `*` / `_` whose
            // neighbours are both word chars (`test_data_set`, `2*3`) is literal TEXT, not a toggle.
            '*' -> if (isInWord()) emit(TypstTokenTypes.TEXT, position + 1) else emit(TypstTokenTypes.STAR, position + 1)
            '_' -> if (isInWord()) emit(TypstTokenTypes.TEXT, position + 1) else emit(TypstTokenTypes.UNDERSCORE, position + 1)
            '\\' -> scanEscapeOrLinebreak()
            '~' -> emit(TypstTokenTypes.SHORTHAND, position + 1)
            '\'', '"' -> emit(TypstTokenTypes.SMART_QUOTE, position + 1)
            '-' -> scanDashOrText()
            '.' -> if (peek(1) == '.' && peek(2) == '.') emit(TypstTokenTypes.SHORTHAND, position + 3) else scanText()
            '@' -> scanRef()
            '<' -> scanLabelOrText()
            'h' -> if (!scanLink()) scanText()
            else -> scanText()
        }
    }

    /** Returns true and emits a line-start marker token, or false to fall through to normal markup. */
    private fun scanLineStartMarker(c: Char): Boolean {
        when (c) {
            '=' -> {
                var end = position
                while (end < bufferEndOffset && buffer[end] == '=') end++
                if (end >= bufferEndOffset || isSpaceOrEol(buffer[end])) {
                    emit(TypstTokenTypes.HEADING_MARKER, end); return true
                }
            }
            '-' -> if (peek(1) != '-' && isSpaceAt(position + 1)) { emit(TypstTokenTypes.LIST_MARKER, position + 1); return true }
            '+' -> if (isSpaceAt(position + 1)) { emit(TypstTokenTypes.ENUM_MARKER, position + 1); return true }
            '/' -> if (isSpaceAt(position + 1)) { emit(TypstTokenTypes.TERM_MARKER, position + 1); return true }
            in '0'..'9' -> {
                var end = position
                while (end < bufferEndOffset && isDigit(buffer[end])) end++
                if (end < bufferEndOffset && buffer[end] == '.' && isSpaceAt(end + 1)) {
                    emit(TypstTokenTypes.ENUM_MARKER, end + 1); return true
                }
            }
        }
        return false
    }

    private fun scanEscapeOrLinebreak() {
        if (position + 1 >= bufferEndOffset) { emit(TypstTokenTypes.LINEBREAK, position + 1); return }
        val next = buffer[position + 1]
        if (next == ' ' || next == '\n' || next == '\r' || next == '\t') {
            emit(TypstTokenTypes.LINEBREAK, position + 1)
        } else {
            emit(TypstTokenTypes.ESCAPE, position + 2)
        }
    }

    private fun scanDashOrText() {
        when {
            peek(1) == '-' && peek(2) == '-' -> emit(TypstTokenTypes.SHORTHAND, position + 3) // ---
            peek(1) == '-' -> emit(TypstTokenTypes.SHORTHAND, position + 2)                    // --
            else -> emit(TypstTokenTypes.TEXT, position + 1)                                   // lone '-'
        }
    }

    /** `@target` reference — the sigil plus a run of identifier / `:` / `.` characters. */
    private fun scanRef() {
        var end = position + 1
        while (end < bufferEndOffset && (isIdentPart(buffer[end]) || buffer[end] == ':' || buffer[end] == '.')) end++
        emit(TypstTokenTypes.REF_MARKER, end)
    }

    /** `<name>` label — falls back to plain text if it is not a well-formed label on one line. */
    private fun scanLabelOrText() {
        if (!isIdentStart(peek(1))) { scanText(); return }
        var end = position + 1
        while (end < bufferEndOffset && (isIdentPart(buffer[end]) || buffer[end] == ':' || buffer[end] == '.')) end++
        if (end < bufferEndOffset && buffer[end] == '>') {
            emit(TypstTokenTypes.LABEL_DEF, end + 1)
        } else {
            scanText()
        }
    }

    /** `http://…` / `https://…` bare link. Returns false if there is no link prefix here. */
    private fun scanLink(): Boolean {
        val http = matchesAt(position, "http://")
        val https = matchesAt(position, "https://")
        if (!http && !https) return false
        var end = position + (if (https) 8 else 7)
        while (end < bufferEndOffset && !isSpace(buffer[end]) && buffer[end] != ']' && buffer[end] != '[') end++
        emit(TypstTokenTypes.LINK, end)
        return true
    }

    /** Plain prose run. Stops at any char that may start a markup-significant token. */
    private fun scanText() {
        var end = position
        while (end < bufferEndOffset) {
            val ch = buffer[end]
            if (isMarkupBreak(ch)) break
            if (ch == ']' && topIsContent()) break
            end++
        }
        if (end == position) end = position + 1 // guarantee forward progress on a lone break char
        emit(TypstTokenTypes.TEXT, end)
    }

    private fun isMarkupBreak(ch: Char): Boolean = when (ch) {
        ' ', '\t', '\r', '\n', FORM_FEED,
        '#', '[', '$', '*', '_', '`', '\\', '@', '<', '~', '\'', '"', '-', '.', '/' -> true
        else -> false
    }

    // ---- code ----

    private fun scanCode(c: Char) {
        when {
            c == '(' -> { emit(TypstTokenTypes.LPAREN, position + 1); push(F_PAREN) }
            c == ')' -> { emit(TypstTokenTypes.RPAREN, position + 1); popIfTop(F_PAREN); endAtomicIfNoTail() }
            c == '{' -> { emit(TypstTokenTypes.LBRACE, position + 1); push(F_BRACE) }
            c == '}' -> { emit(TypstTokenTypes.RBRACE, position + 1); popIfTop(F_BRACE); endAtomicIfNoTail() }
            c == '[' -> { emit(TypstTokenTypes.LBRACKET, position + 1); push(F_CONTENT) }
            c == ']' -> { emit(TypstTokenTypes.RBRACKET, position + 1); popIfTop(F_CONTENT); endAtomicIfNoTail() }
            c == '$' -> { emit(TypstTokenTypes.DOLLAR, position + 1); push(F_MATH) }
            c == '"' -> { scanString(); endAtomicIfNoTail() }
            c == '\\' -> scanCodeEscape()
            c == '=' -> when (peek(1)) {
                '=' -> emit(TypstTokenTypes.EQ_EQ, position + 2)
                '>' -> emit(TypstTokenTypes.ARROW, position + 2)
                else -> emit(TypstTokenTypes.EQ, position + 1)
            }
            c == '!' -> if (peek(1) == '=') emit(TypstTokenTypes.EXCL_EQ, position + 2) else emit(TokenType.BAD_CHARACTER, position + 1)
            c == '<' -> if (peek(1) == '=') emit(TypstTokenTypes.LT_EQ, position + 2) else emit(TypstTokenTypes.LT, position + 1)
            c == '>' -> if (peek(1) == '=') emit(TypstTokenTypes.GT_EQ, position + 2) else emit(TypstTokenTypes.GT, position + 1)
            c == '+' -> if (peek(1) == '=') emit(TypstTokenTypes.PLUS_EQ, position + 2) else emit(TypstTokenTypes.PLUS, position + 1)
            c == '-' -> if (peek(1) == '=') emit(TypstTokenTypes.MINUS_EQ, position + 2) else emit(TypstTokenTypes.MINUS, position + 1)
            c == '*' -> if (peek(1) == '=') emit(TypstTokenTypes.STAR_EQ, position + 2) else emit(TypstTokenTypes.STAR, position + 1)
            c == '/' -> if (peek(1) == '=') emit(TypstTokenTypes.SLASH_EQ, position + 2) else emit(TypstTokenTypes.SLASH, position + 1)
            c == '.' -> when {
                peek(1) == '.' -> emit(TypstTokenTypes.DOT_DOT, position + 2)
                isDigit(peek(1)) -> scanNumber() // leading-dot number: `.5`, `.2em`
                else -> emit(TypstTokenTypes.DOT, position + 1)
            }
            c == '^' -> emit(TypstTokenTypes.HAT, position + 1)
            c == ',' -> emit(TypstTokenTypes.COMMA, position + 1)
            c == ';' -> emit(TypstTokenTypes.SEMICOLON, position + 1)
            c == ':' -> emit(TypstTokenTypes.COLON, position + 1)
            c == '_' -> if (isIdentPart(peek(1))) { scanIdentifier(); endAtomicIfNoTail() } else emit(TypstTokenTypes.UNDERSCORE, position + 1)
            isDigit(c) -> { scanNumber(); endAtomicIfNoTail() }
            isIdentStart(c) -> { scanIdentifier(); endAtomicIfNoTail() }
            else -> emit(TokenType.BAD_CHARACTER, position + 1) // bounded single-char fallback; never TEXT in code
        }
    }

    private fun scanCodeEscape() {
        val next = peek(1)
        if (next == ' ' || next == '\n' || next == '\r') emit(TokenType.BAD_CHARACTER, position + 1)
        else emit(TypstTokenTypes.ESCAPE, position + 2)
    }

    private fun scanString() {
        var end = position + 1
        while (end < bufferEndOffset && buffer[end] != '"' && buffer[end] != '\n') {
            if (buffer[end] == '\\' && end + 1 < bufferEndOffset && buffer[end + 1] != '\n') end++ // escaped char
            end++
        }
        if (end < bufferEndOffset && buffer[end] == '"') end++ // include the closing quote when present
        emit(TypstTokenTypes.STRING, end)
    }

    private fun scanNumber() {
        var end = position
        while (end < bufferEndOffset && isDigit(buffer[end])) end++
        var isFloat = false
        // fractional part (only when a digit follows the dot, so `1..2` stays a range)
        if (end < bufferEndOffset && buffer[end] == '.' && end + 1 < bufferEndOffset && isDigit(buffer[end + 1])) {
            end++
            while (end < bufferEndOffset && isDigit(buffer[end])) end++
            isFloat = true
        }
        // exponent
        if (end < bufferEndOffset && (buffer[end] == 'e' || buffer[end] == 'E')) {
            var exp = end + 1
            if (exp < bufferEndOffset && (buffer[exp] == '+' || buffer[exp] == '-')) exp++
            if (exp < bufferEndOffset && isDigit(buffer[exp])) {
                exp++
                while (exp < bufferEndOffset && isDigit(buffer[exp])) exp++
                end = exp
                isFloat = true
            }
        }
        // measurement unit / ratio
        if (end < bufferEndOffset && buffer[end] == '%') {
            emit(TypstTokenTypes.NUMERIC_LITERAL, end + 1); return
        }
        var unitEnd = end
        while (unitEnd < bufferEndOffset && Character.isLetter(buffer[unitEnd])) unitEnd++
        if (unitEnd > end && buffer.subSequence(end, unitEnd).toString() in UNITS) {
            emit(TypstTokenTypes.NUMERIC_LITERAL, unitEnd); return
        }
        emit(if (isFloat) TypstTokenTypes.FLOAT_LITERAL else TypstTokenTypes.INTEGER_LITERAL, end)
    }

    private fun scanIdentifier() {
        var end = position
        while (end < bufferEndOffset && isIdentPart(buffer[end])) end++
        val word = buffer.subSequence(position, end).toString()
        emit(KEYWORDS[word] ?: TypstTokenTypes.IDENTIFIER, end)
    }

    // ---- math ----

    private fun scanMath(c: Char) {
        when {
            c == '$' -> { emit(TypstTokenTypes.DOLLAR, position + 1); popIfTop(F_MATH) }
            c == '#' -> {
                emit(TypstTokenTypes.HASH, position + 1)
                val word = peekWord(position + 1)
                push(if (word in STMT_KEYWORDS) F_HASH_STMT else F_HASH_ATOMIC)
            }
            c == '(' || c == '[' || c == '{' -> {
                emit(delimToken(c), position + 1); push(F_MATH_DELIM)
            }
            c == ')' || c == ']' || c == '}' -> {
                emit(delimToken(c), position + 1); popIfTop(F_MATH_DELIM)
            }
            c == '&' -> emit(TypstTokenTypes.MATH_ALIGN, position + 1)
            c == '^' -> emit(TypstTokenTypes.HAT, position + 1)
            c == '_' -> emit(TypstTokenTypes.UNDERSCORE, position + 1)
            c == '\'' -> {
                var end = position
                while (end < bufferEndOffset && buffer[end] == '\'') end++
                emit(TypstTokenTypes.MATH_PRIMES, end)
            }
            c == '"' -> scanString()
            c == '\\' -> scanCodeEscape()
            isMathShorthandStart(c) -> scanMathShorthand(c)
            isIdentStart(c) -> {
                var end = position
                while (end < bufferEndOffset && isIdentPart(buffer[end])) end++
                emit(TypstTokenTypes.MATH_IDENT, end)
            }
            else -> emit(TypstTokenTypes.MATH_TEXT, position + 1) // digits / symbols; never BAD_CHARACTER noise
        }
    }

    private fun isMathShorthandStart(c: Char): Boolean =
        c == '<' || c == '>' || c == '-' || c == '!' || c == ':' || c == '='

    private fun scanMathShorthand(c: Char) {
        val two = when {
            c == '<' && peek(1) == '=' -> true
            c == '>' && peek(1) == '=' -> true
            c == '!' && peek(1) == '=' -> true
            c == ':' && peek(1) == '=' -> true
            c == '=' && (peek(1) == '=' || peek(1) == '>') -> true
            c == '-' && (peek(1) == '>' || peek(1) == '-') -> true
            else -> false
        }
        if (two) emit(TypstTokenTypes.MATH_SHORTHAND, position + 2) else emit(TypstTokenTypes.MATH_TEXT, position + 1)
    }

    private fun delimToken(c: Char): IElementType = when (c) {
        '(' -> TypstTokenTypes.LPAREN
        ')' -> TypstTokenTypes.RPAREN
        '[' -> TypstTokenTypes.LBRACKET
        ']' -> TypstTokenTypes.RBRACKET
        '{' -> TypstTokenTypes.LBRACE
        else -> TypstTokenTypes.RBRACE
    }

    // ---- primitives ----

    private fun emit(type: IElementType, end: Int) {
        currentTokenType = type
        currentTokenEnd = end
        lineStart = false
    }

    private fun peek(ahead: Int): Char {
        val index = position + ahead
        return if (index < bufferEndOffset) buffer[index] else ' '
    }

    private fun isSpaceAt(index: Int): Boolean =
        index < bufferEndOffset && (buffer[index] == ' ' || buffer[index] == '\t')

    private fun matchesAt(from: Int, literal: String): Boolean {
        if (from + literal.length > bufferEndOffset) return false
        for (i in literal.indices) if (buffer[from + i] != literal[i]) return false
        return true
    }

    private fun peekWord(from: Int): String {
        if (from >= bufferEndOffset || !isIdentStart(buffer[from])) return ""
        var end = from
        while (end < bufferEndOffset && isIdentPart(buffer[end])) end++
        return buffer.subSequence(from, end).toString()
    }

    private fun isSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == FORM_FEED

    private fun isSpaceOrEol(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == FORM_FEED

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    /**
     * Mirrors typst's `in_word()` gate for markup emphasis toggles. The scan cursor [position] points
     * at the `*` / `_`; the marker is "inside a word" when both the previous and the next character are
     * word chars, in which case it is literal text rather than a strong / emphasis toggle. The
     * lookaround reads [buffer] directly (always the whole document, even on an incremental restart),
     * so the classification is identical on the forward pass and on any restart; it touches no frame or
     * line-start state, so the packed restart state — and therefore `checkCorrectRestart` — is unchanged.
     */
    private fun isInWord(): Boolean {
        val prev = if (position > 0) buffer[position - 1] else return false
        val next = if (position + 1 < bufferEndOffset) buffer[position + 1] else return false
        return isWordChar(prev) && isWordChar(next)
    }

    private fun isWordChar(c: Char): Boolean = Character.isLetterOrDigit(c)

    private fun isIdentStart(c: Char): Boolean = c == '_' || Character.isLetter(c)

    private fun isIdentPart(c: Char): Boolean = c == '_' || c == '-' || Character.isLetterOrDigit(c)
}
