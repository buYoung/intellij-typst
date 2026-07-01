package com.livteam.typninja.language.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.psi.TypstElementTypes as E
import com.livteam.typninja.language.psi.TypstTokenTypes as T

/**
 * Hand-written, recoverable recursive-descent + precedence-climbing parser for Typst (0.15.0),
 * modelled on `typst-syntax`'s hand-written parser but adapted to IntelliJ's eager, flat token stream.
 *
 * The [com.livteam.typninja.language.lexer.TypstLexer] already owns markup / code / math mode
 * switching, so the token TYPE unambiguously tells this parser which grammar to apply: a `#`
 * ([T.HASH]) introduces code, a `$` ([T.DOLLAR]) an equation, `[` inside markup a content block, and
 * inside a code region the operators / keywords / delimiters all arrive as code tokens. This parser
 * turns that stream into a real AST (see [E]).
 *
 * ## Grammar
 *  - markup: [parseMarkupBlock] → headings / list / enum / term items, strong/emph, refs, content
 *    blocks, equations, `#` code, and prose runs.
 *  - code:   [parseCodeExpr] is precedence climbing (assignment < or < and < comparison/`in` <
 *    additive < multiplicative < unary < postfix < primary); [parsePostfix] handles field access,
 *    calls and trailing content args; [parsePrimary] handles literals, blocks, collections, closures
 *    and every statement (let/set/show/context/if/while/for/import/include/return/break/continue).
 *  - collections: `(` is parsed optimistically as array/dict/parenthesized and reinterpreted as a
 *    closure parameter list or a destructuring assignment via [PsiBuilder.Marker.rollbackTo] (mirrors
 *    typst's `Checkpoint`/`restore`).
 *  - math: shallow but structured — never errors; unknown math fragments stay leaves, nested
 *    delimited groups become [E.MATH_DELIMITED], and embedded `#` code is parsed.
 *
 * ## Recovery invariants (hard requirements)
 *  - Every parse step consumes at least one token, so every loop terminates and the file node spans
 *    the whole document. Loops additionally assert forward progress via [PsiBuilder.rawTokenIndex].
 *  - Unexpected tokens become BOUNDED single-token error leaves ([consumeErrorToken]); a stop-set
 *    (closing delimiter / [T.PARBREAK] / [T.SEMICOLON] / EOF) keeps recovery from swallowing the rest
 *    of the file. Because the lexer keeps modes correct, VALID Typst never reaches an error branch.
 *  - Incomplete groups / strings / equations simply end their node quietly (no error), so unterminated
 *    input degrades gracefully.
 *  - Recursion is depth-guarded ([MAX_RECURSION_DEPTH]); past the cap a construct consumes its opener
 *    as a leaf instead of descending, so pathological nesting yields a bounded tree, never a
 *    StackOverflowError.
 *
 * Whitespace and comments are skipped by [PsiBuilder] (declared in the parser definition), so this
 * parser only ever sees meaningful tokens. [T.PARBREAK] is NOT whitespace: it is a real paragraph
 * boundary this parser consumes explicitly.
 */
class TypstParser : PsiParser {

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val fileMarker = builder.mark()
        parseMarkupBlock(builder, nested = false, depth = 0)
        // Defensive: consume anything left (should not happen — the loop runs to EOF).
        while (!builder.eof()) consumeErrorToken(builder, "Unexpected token")
        fileMarker.done(root)
        return builder.treeBuilt
    }

    // ======================================================================================
    // markup
    // ======================================================================================

    /**
     * Block-level markup loop. Runs to EOF, or to the matching `]` when [nested] inside a content
     * block. Line-start markers open block items; everything else is an inline element.
     */
    private fun parseMarkupBlock(builder: PsiBuilder, nested: Boolean, depth: Int) {
        while (!builder.eof()) {
            val t = builder.tokenType
            if (nested && t == T.RBRACKET) break
            val before = builder.rawTokenIndex()
            when {
                t == T.PARBREAK -> builder.advanceLexer() // paragraph separator leaf
                t == T.HEADING_MARKER -> parseBlockItem(builder, E.HEADING, nested, depth)
                t == T.LIST_MARKER -> parseBlockItem(builder, E.LIST_ITEM, nested, depth)
                t == T.ENUM_MARKER -> parseBlockItem(builder, E.ENUM_ITEM, nested, depth)
                t == T.TERM_MARKER -> parseBlockItem(builder, E.TERM_ITEM, nested, depth)
                else -> parseInlineElement(builder, nested, depth)
            }
            if (builder.rawTokenIndex() == before) consumeErrorToken(builder, "Unexpected token")
        }
    }

    /** A heading / list / enum / term item: its marker then an inline run to the line/paragraph end. */
    private fun parseBlockItem(builder: PsiBuilder, type: IElementType, nested: Boolean, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // the *_MARKER token
        parseInlineSequence(builder, nested, depth, stopToggle = null)
        marker.done(type)
    }

    /**
     * Consume inline elements until a natural boundary: [T.PARBREAK], EOF, a line-start block marker,
     * the matching `]` when [nested], or [stopToggle] (the closing `*` / `_` of a strong/emph run).
     */
    private fun parseInlineSequence(builder: PsiBuilder, nested: Boolean, depth: Int, stopToggle: IElementType?) {
        while (!builder.eof()) {
            val t = builder.tokenType
            if (t == T.PARBREAK) break
            if (nested && t == T.RBRACKET) break
            if (isBlockMarker(t)) break
            if (stopToggle != null && t == stopToggle) break
            val before = builder.rawTokenIndex()
            parseInlineElement(builder, nested, depth)
            if (builder.rawTokenIndex() == before) { consumeErrorToken(builder, "Unexpected token"); break }
        }
    }

    /** One inline markup construct. Always advances at least one token. */
    private fun parseInlineElement(builder: PsiBuilder, nested: Boolean, depth: Int) {
        when (val t = builder.tokenType) {
            T.HASH -> parseCodeHash(builder, depth)
            T.DOLLAR -> parseEquation(builder, depth)
            T.REF_MARKER -> parseRef(builder, depth)
            T.LBRACKET -> parseContentBlock(builder, depth)
            T.RAW_TEXT -> wrapSingle(builder, E.RAW)
            T.STRING -> wrapSingle(builder, E.STRING_LITERAL) // defensive; markup emits SMART_QUOTE
            T.STAR -> parseToggle(builder, E.STRONG, T.STAR, nested, depth)
            T.UNDERSCORE -> parseToggle(builder, E.EMPH, T.UNDERSCORE, nested, depth)
            else ->
                if (isProse(t)) parseProseRun(builder)
                else consumeErrorToken(builder, "Unexpected token")
        }
    }

    /** A run of consecutive prose leaves grouped into one [E.MARKUP]. */
    private fun parseProseRun(builder: PsiBuilder) {
        val marker = builder.mark()
        while (!builder.eof() && isProse(builder.tokenType)) builder.advanceLexer()
        marker.done(E.MARKUP)
    }

    /** `*strong*` / `_emph_`. Unterminated toggles simply end the node (recovery, no error). */
    private fun parseToggle(builder: PsiBuilder, type: IElementType, toggle: IElementType, nested: Boolean, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // opening toggle
        if (depth < MAX_RECURSION_DEPTH) {
            parseInlineSequence(builder, nested, depth + 1, stopToggle = toggle)
        }
        if (builder.tokenType == toggle) builder.advanceLexer() // closing toggle when present
        marker.done(type)
    }

    /** `@target` reference, with an optional immediately-following `[ ... ]` content argument. */
    private fun parseRef(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // REF_MARKER
        if (builder.tokenType == T.LBRACKET) parseContentBlock(builder, depth)
        marker.done(E.REF)
    }

    /**
     * A `[ ... ]` content block whose body is nested markup (may contain paragraphs, so [T.PARBREAK]
     * does NOT end it). Bounded at the matching `]` or EOF; past the recursion cap the body is skipped
     * so deeply nested `[` cannot StackOverflow.
     */
    private fun parseContentBlock(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // [
        if (depth < MAX_RECURSION_DEPTH) parseMarkupBlock(builder, nested = true, depth + 1)
        if (builder.tokenType == T.RBRACKET) builder.advanceLexer() // ]
        marker.done(E.CONTENT_BLOCK)
    }

    // ======================================================================================
    // code
    // ======================================================================================

    /** A `#`-introduced code expression. The lexer already bounds the atomic/statement region. */
    private fun parseCodeHash(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // HASH
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        marker.done(E.CODE_EXPRESSION)
    }

    /** `{ ... }` code block: a sequence of code expressions bounded by the matching `}`. */
    private fun parseCodeBlock(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // {
        if (depth < MAX_RECURSION_DEPTH) parseCodeExprs(builder, T.RBRACE, depth + 1)
        if (builder.tokenType == T.RBRACE) builder.advanceLexer() // }
        marker.done(E.CODE_BLOCK)
    }

    /** Statements / expressions until [closer] or EOF. Separators are `;` and (invisible) newlines. */
    private fun parseCodeExprs(builder: PsiBuilder, closer: IElementType, depth: Int) {
        while (!builder.eof() && builder.tokenType != closer) {
            val t = builder.tokenType
            if (t == T.SEMICOLON) { builder.advanceLexer(); continue }
            if (isCloser(t)) { consumeErrorToken(builder, "Unmatched closing delimiter"); continue }
            val before = builder.rawTokenIndex()
            if (canStartExpr(t)) parseCodeExpr(builder, 0, depth) else consumeErrorToken(builder, "Unexpected token")
            if (builder.tokenType == T.SEMICOLON) builder.advanceLexer()
            if (builder.rawTokenIndex() == before) consumeErrorToken(builder, "Unexpected token")
        }
    }

    /**
     * Precedence-climbing expression parser (typst `code_expr_prec` order). Left-associative folding
     * uses the [PsiBuilder.Marker.precede] idiom; assignment is right-associative. The caller must
     * guarantee [canStartExpr] holds (or accept that nothing is produced).
     */
    private fun parseCodeExpr(builder: PsiBuilder, minPrecedence: Int, depth: Int) {
        if (builder.eof() || !canStartExpr(builder.tokenType)) return
        if (depth > MAX_RECURSION_DEPTH) { builder.advanceLexer(); return }
        var left = builder.mark()
        parseUnary(builder, depth)
        while (true) {
            val op = peekBinaryOp(builder)
            if (op == null || op.precedence < minPrecedence) { left.drop(); break }
            consumeOp(builder, op)
            val nextMin = if (op.rightAssociative) op.precedence else op.precedence + 1
            parseCodeExpr(builder, nextMin, depth + 1)
            left.done(E.BINARY)
            left = left.precede()
        }
    }

    /** Unary prefix (`not` / `-` / `+`) or a postfix expression. */
    private fun parseUnary(builder: PsiBuilder, depth: Int) {
        val t = builder.tokenType
        val isUnaryPrefix = (t == T.MINUS || t == T.PLUS) ||
            (t == T.KW_NOT && builder.lookAhead(1) != T.KW_IN) // `not in` is a binary operator
        if (isUnaryPrefix) {
            val marker = builder.mark()
            builder.advanceLexer() // operator
            if (canStartExpr(builder.tokenType)) parseUnary(builder, depth + 1)
            marker.done(E.UNARY)
            return
        }
        parsePostfix(builder, depth)
    }

    /** Primary followed by any number of postfix `.field`, `(args)` and trailing `[content]`. */
    private fun parsePostfix(builder: PsiBuilder, depth: Int) {
        var left = builder.mark()
        parsePrimary(builder, depth)
        while (true) {
            when (builder.tokenType) {
                T.DOT -> {
                    builder.advanceLexer() // .
                    if (builder.tokenType == T.IDENTIFIER) builder.advanceLexer() // field name
                    left.done(E.FIELD_ACCESS); left = left.precede()
                }
                T.LPAREN -> {
                    parseArgs(builder, depth)
                    if (builder.tokenType == T.LBRACKET) parseContentBlock(builder, depth) // f(..)[..]
                    left.done(E.FUNC_CALL); left = left.precede()
                }
                T.LBRACKET -> { // f[..]
                    parseContentBlock(builder, depth)
                    left.done(E.FUNC_CALL); left = left.precede()
                }
                else -> { left.drop(); break }
            }
        }
    }

    /** A primary expression / statement. Always advances at least one token when it produces a node. */
    private fun parsePrimary(builder: PsiBuilder, depth: Int) {
        if (depth > MAX_RECURSION_DEPTH) { builder.advanceLexer(); return }
        when (val t = builder.tokenType) {
            T.KW_LET -> parseLet(builder, depth)
            T.KW_SET -> parseSet(builder, depth)
            T.KW_SHOW -> parseShow(builder, depth)
            T.KW_CONTEXT -> parseUnaryStatement(builder, T.KW_CONTEXT, E.CONTEXTUAL, depth)
            T.KW_IF -> parseConditional(builder, depth)
            T.KW_WHILE -> parseWhile(builder, depth)
            T.KW_FOR -> parseFor(builder, depth)
            T.KW_IMPORT -> parseImport(builder, depth)
            T.KW_INCLUDE -> parseUnaryStatement(builder, T.KW_INCLUDE, E.MODULE_INCLUDE, depth)
            T.KW_RETURN -> parseReturn(builder, depth)
            T.KW_BREAK -> wrapSingle(builder, E.LOOP_BREAK)
            T.KW_CONTINUE -> wrapSingle(builder, E.LOOP_CONTINUE)
            T.LPAREN -> parseParenExpr(builder, depth)
            T.LBRACE -> parseCodeBlock(builder, depth)
            T.LBRACKET -> parseContentBlock(builder, depth)
            T.DOLLAR -> parseEquation(builder, depth)
            T.STRING -> wrapSingle(builder, E.STRING_LITERAL)
            T.RAW_TEXT -> wrapSingle(builder, E.RAW)
            T.IDENTIFIER ->
                if (builder.lookAhead(1) == T.ARROW) parseIdentClosure(builder, depth)
                else builder.advanceLexer() // plain identifier leaf
            else ->
                if (isLiteral(t)) builder.advanceLexer() // number / bool / none / auto / underscore
                else consumeErrorToken(builder, "Unexpected token")
        }
    }

    // ---- statements ----

    /** `let (pattern | ident params?) (= value)?`. */
    private fun parseLet(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // let
        when (builder.tokenType) {
            T.LPAREN -> parseDestructuring(builder, depth)
            T.UNDERSCORE -> builder.advanceLexer()
            T.IDENTIFIER -> {
                builder.advanceLexer() // name
                if (builder.tokenType == T.LPAREN) parseParams(builder, depth) // let f(x) = ...
            }
        }
        if (builder.tokenType == T.EQ) {
            builder.advanceLexer() // =
            if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        }
        marker.done(E.LET_BINDING)
    }

    /** `set target (if cond)?`. */
    private fun parseSet(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // set
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        if (builder.tokenType == T.KW_IF) {
            builder.advanceLexer() // if
            if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        }
        marker.done(E.SET_RULE)
    }

    /** `show (selector)? : transform`. */
    private fun parseShow(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // show
        if (builder.tokenType != T.COLON && canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        if (builder.tokenType == T.COLON) {
            builder.advanceLexer() // :
            if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        }
        marker.done(E.SHOW_RULE)
    }

    /** `context expr` / `include expr` — a keyword followed by one expression. */
    private fun parseUnaryStatement(builder: PsiBuilder, keyword: IElementType, type: IElementType, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // keyword
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        marker.done(type)
    }

    /** `if cond block (else (conditional | block))?`. */
    private fun parseConditional(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // if
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1) // condition
        parseBlock(builder, depth)
        if (builder.tokenType == T.KW_ELSE) {
            builder.advanceLexer() // else
            if (builder.tokenType == T.KW_IF) parseConditional(builder, depth) else parseBlock(builder, depth)
        }
        marker.done(E.CONDITIONAL)
    }

    /** `while cond block`. */
    private fun parseWhile(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // while
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        parseBlock(builder, depth)
        marker.done(E.WHILE_LOOP)
    }

    /** `for pattern in iterable block`. */
    private fun parseFor(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // for
        parsePattern(builder, depth)
        if (builder.tokenType == T.KW_IN) builder.advanceLexer()
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        parseBlock(builder, depth)
        marker.done(E.FOR_LOOP)
    }

    /** `import source (: (* | items))? (as ident)?`. */
    private fun parseImport(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // import
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1) // source
        if (builder.tokenType == T.COLON) {
            builder.advanceLexer() // :
            if (builder.tokenType == T.STAR) builder.advanceLexer() else parseImportItems(builder, depth)
        }
        if (builder.tokenType == T.KW_AS) {
            builder.advanceLexer() // as
            if (builder.tokenType == T.IDENTIFIER) builder.advanceLexer()
        }
        marker.done(E.MODULE_IMPORT)
    }

    /** `item (, item)*` where each item is `path (as name)?`; paths and names are consumed as leaves. */
    private fun parseImportItems(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        while (!builder.eof()) {
            val t = builder.tokenType
            if (t == T.PARBREAK || isCloser(t)) break
            when {
                t == T.IDENTIFIER || t == T.DOT || t == T.STAR -> builder.advanceLexer() // path / glob segment
                t == T.KW_AS -> { builder.advanceLexer(); if (builder.tokenType == T.IDENTIFIER) builder.advanceLexer() }
                t == T.COMMA -> builder.advanceLexer()
                else -> break
            }
        }
        marker.done(E.IMPORT_ITEMS)
    }

    /** `return value?`. */
    private fun parseReturn(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // return
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        marker.done(E.FUNC_RETURN)
    }

    /** A block is a `{ ... }` code block or a `[ ... ]` content block; missing block is tolerated. */
    private fun parseBlock(builder: PsiBuilder, depth: Int) {
        when (builder.tokenType) {
            T.LBRACE -> parseCodeBlock(builder, depth)
            T.LBRACKET -> parseContentBlock(builder, depth)
            else -> {} // recovery: leave the node without a block rather than flag an error
        }
    }

    // ---- collections, args, params, closures, destructuring ----

    /**
     * `( ... )` in primary position. Parsed optimistically as array / dict / parenthesized, then
     * reinterpreted via [PsiBuilder.Marker.rollbackTo]: `=>` makes it a closure parameter list, a
     * following `=` makes it a destructuring assignment target.
     */
    private fun parseParenExpr(builder: PsiBuilder, depth: Int) {
        val outer = builder.mark()      // may become DESTRUCT_ASSIGNMENT; else dropped
        val rollback = builder.mark()   // rewind point for the closure re-interpretation
        parseCollection(builder, depth)
        when (builder.tokenType) {
            T.ARROW -> {
                rollback.rollbackTo() // discard the collection tree, re-read the same tokens as params
                parseClosure(builder, depth)
                outer.drop()
            }
            T.EQ -> {
                rollback.drop()
                builder.advanceLexer() // =
                if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
                outer.done(E.DESTRUCT_ASSIGNMENT)
            }
            else -> { rollback.drop(); outer.drop() }
        }
    }

    /**
     * Parse `( ... )` deciding [E.ARRAY] / [E.DICT] / [E.PARENTHESIZED]:
     *  - `()`                 -> empty ARRAY
     *  - `(:)`                -> empty DICT
     *  - any named/keyed item -> DICT
     *  - exactly one item, no trailing comma -> PARENTHESIZED
     *  - otherwise            -> ARRAY
     */
    private fun parseCollection(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // (
        var isDict = false
        var itemCount = 0
        var sawComma = false
        if (builder.tokenType == T.COLON && builder.lookAhead(1) == T.RPAREN) {
            builder.advanceLexer() // : (empty dict)
            isDict = true
        } else if (depth < MAX_RECURSION_DEPTH) {
            while (!builder.eof() && builder.tokenType != T.RPAREN) {
                val before = builder.rawTokenIndex()
                if (parseEntry(builder, depth + 1)) isDict = true
                itemCount++
                if (builder.tokenType == T.COMMA) { builder.advanceLexer(); sawComma = true } else break
                if (builder.rawTokenIndex() == before) break
            }
        }
        if (builder.tokenType == T.RPAREN) builder.advanceLexer() // )
        val type = when {
            isDict -> E.DICT
            itemCount == 1 && !sawComma -> E.PARENTHESIZED
            else -> E.ARRAY
        }
        marker.done(type)
    }

    /** One `( ... )` entry. Returns true when it is dict-shaped (named / keyed). */
    private fun parseEntry(builder: PsiBuilder, depth: Int): Boolean {
        val t = builder.tokenType
        return when {
            t == T.DOT_DOT -> { parseSpread(builder, depth); false }
            t == T.IDENTIFIER && builder.lookAhead(1) == T.COLON -> { parseNamedOrKeyed(builder, E.NAMED, depth); true }
            t == T.STRING && builder.lookAhead(1) == T.COLON -> { parseNamedOrKeyed(builder, E.KEYED, depth); true }
            else -> { if (canStartExpr(t)) parseCodeExpr(builder, 0, depth) else consumeErrorToken(builder, "Unexpected token"); false }
        }
    }

    /** `key : value` (named uses an identifier key, keyed a string key). */
    private fun parseNamedOrKeyed(builder: PsiBuilder, type: IElementType, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // key
        builder.advanceLexer() // :
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        marker.done(type)
    }

    /** `.. value` spread / sink. */
    private fun parseSpread(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // ..
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        marker.done(E.SPREAD)
    }

    /** `( arg (, arg)* )` in a call. */
    private fun parseArgs(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // (
        if (depth < MAX_RECURSION_DEPTH) {
            while (!builder.eof() && builder.tokenType != T.RPAREN) {
                val before = builder.rawTokenIndex()
                parseEntry(builder, depth + 1)
                if (builder.tokenType == T.COMMA) builder.advanceLexer() else break
                if (builder.rawTokenIndex() == before) break
            }
        }
        if (builder.tokenType == T.RPAREN) builder.advanceLexer() // )
        marker.done(E.ARGS)
    }

    /** `( param (, param)* )` in a closure / function definition. */
    private fun parseParams(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // (
        if (depth < MAX_RECURSION_DEPTH) {
            while (!builder.eof() && builder.tokenType != T.RPAREN) {
                val before = builder.rawTokenIndex()
                parseParam(builder, depth + 1)
                if (builder.tokenType == T.COMMA) builder.advanceLexer() else break
                if (builder.rawTokenIndex() == before) break
            }
        }
        if (builder.tokenType == T.RPAREN) builder.advanceLexer() // )
        marker.done(E.PARAMS)
    }

    /** A closure parameter: `name(: default)?` | `..sink` | `_` | nested destructuring. */
    private fun parseParam(builder: PsiBuilder, depth: Int) {
        when (val t = builder.tokenType) {
            T.DOT_DOT -> parseSpread(builder, depth)
            T.LPAREN -> parseDestructuring(builder, depth)
            T.UNDERSCORE -> builder.advanceLexer()
            T.IDENTIFIER ->
                if (builder.lookAhead(1) == T.COLON) parseNamedOrKeyed(builder, E.NAMED, depth) else builder.advanceLexer()
            else -> if (canStartExpr(t)) parseCodeExpr(builder, 0, depth) else consumeErrorToken(builder, "Unexpected token")
        }
    }

    /** `( pattern (, pattern)* )` destructuring pattern. */
    private fun parseDestructuring(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // (
        if (depth < MAX_RECURSION_DEPTH) {
            while (!builder.eof() && builder.tokenType != T.RPAREN) {
                val before = builder.rawTokenIndex()
                when (val t = builder.tokenType) {
                    T.DOT_DOT -> parseSpread(builder, depth + 1)
                    T.LPAREN -> parseDestructuring(builder, depth + 1)
                    T.IDENTIFIER ->
                        if (builder.lookAhead(1) == T.COLON) parseNamedOrKeyed(builder, E.NAMED, depth + 1) else builder.advanceLexer()
                    T.UNDERSCORE -> builder.advanceLexer()
                    else -> if (canStartExpr(t)) parseCodeExpr(builder, 0, depth + 1) else consumeErrorToken(builder, "Unexpected token")
                }
                if (builder.tokenType == T.COMMA) builder.advanceLexer() else break
                if (builder.rawTokenIndex() == before) break
            }
        }
        if (builder.tokenType == T.RPAREN) builder.advanceLexer() // )
        marker.done(E.DESTRUCTURING)
    }

    /** `(params) => body` (after a rollback confirmed the `=>`). */
    private fun parseClosure(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        parseParams(builder, depth)
        if (builder.tokenType == T.ARROW) builder.advanceLexer()
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        marker.done(E.CLOSURE)
    }

    /** `ident => body` single-parameter closure. */
    private fun parseIdentClosure(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // ident
        builder.advanceLexer() // =>
        if (canStartExpr(builder.tokenType)) parseCodeExpr(builder, 0, depth + 1)
        marker.done(E.CLOSURE)
    }

    /** A `for`/`let` pattern: identifier, `_`, or destructuring. */
    private fun parsePattern(builder: PsiBuilder, depth: Int) {
        when (builder.tokenType) {
            T.LPAREN -> parseDestructuring(builder, depth)
            T.UNDERSCORE, T.IDENTIFIER -> builder.advanceLexer()
            else -> {}
        }
    }

    // ======================================================================================
    // math (shallow but structured — never errors)
    // ======================================================================================

    /** `$ ... $` equation. Kept under the [E.MATH] region name for downstream stability. */
    private fun parseEquation(builder: PsiBuilder, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // $
        if (depth < MAX_RECURSION_DEPTH) parseMathGroup(builder, closer = null, depth + 1)
        if (builder.tokenType == T.DOLLAR) builder.advanceLexer() // closing $
        marker.done(E.MATH)
    }

    /**
     * Math tokens up to [closer] (null at the equation top), the closing `$`, [T.PARBREAK] (the lexer
     * recovers an unclosed equation at a blank line), or EOF. Embedded `#` code and nested delimited
     * groups are structured; everything else stays a leaf. This never produces an error.
     */
    private fun parseMathGroup(builder: PsiBuilder, closer: IElementType?, depth: Int) {
        while (!builder.eof()) {
            val t = builder.tokenType
            if (t == T.DOLLAR || t == T.PARBREAK) break
            if (closer != null && t == closer) break
            when {
                t == T.HASH -> parseCodeHash(builder, depth)
                t == T.LPAREN -> parseMathDelimited(builder, T.RPAREN, depth)
                t == T.LBRACE -> parseMathDelimited(builder, T.RBRACE, depth)
                t == T.LBRACKET -> parseMathDelimited(builder, T.RBRACKET, depth)
                t == T.STRING -> wrapSingle(builder, E.STRING_LITERAL)
                t == T.RAW_TEXT -> wrapSingle(builder, E.RAW)
                else -> builder.advanceLexer() // math ident / text / operator / attach / align / prime
            }
        }
    }

    /** A nested `( )` / `[ ]` / `{ }` delimited math group. */
    private fun parseMathDelimited(builder: PsiBuilder, closer: IElementType, depth: Int) {
        val marker = builder.mark()
        builder.advanceLexer() // opener
        if (depth < MAX_RECURSION_DEPTH) parseMathGroup(builder, closer, depth + 1)
        if (builder.tokenType == closer) builder.advanceLexer() // closer
        marker.done(E.MATH_DELIMITED)
    }

    // ======================================================================================
    // helpers
    // ======================================================================================

    private fun wrapSingle(builder: PsiBuilder, type: IElementType) {
        val marker = builder.mark()
        builder.advanceLexer()
        marker.done(type)
    }

    /** Bounded single-token error leaf — never swallows more than the one offending token. */
    private fun consumeErrorToken(builder: PsiBuilder, message: String) {
        val marker = builder.mark()
        builder.advanceLexer()
        marker.error(message)
    }

    private data class BinaryOp(val precedence: Int, val rightAssociative: Boolean, val twoTokens: Boolean)

    /** The binary operator at the cursor (handling the two-token `not in`), or null. */
    private fun peekBinaryOp(builder: PsiBuilder): BinaryOp? = when (builder.tokenType) {
        T.EQ, T.PLUS_EQ, T.MINUS_EQ, T.STAR_EQ, T.SLASH_EQ -> BinaryOp(1, rightAssociative = true, twoTokens = false)
        T.KW_OR -> BinaryOp(2, false, false)
        T.KW_AND -> BinaryOp(3, false, false)
        T.EQ_EQ, T.EXCL_EQ, T.LT, T.LT_EQ, T.GT, T.GT_EQ, T.KW_IN -> BinaryOp(4, false, false)
        T.KW_NOT -> if (builder.lookAhead(1) == T.KW_IN) BinaryOp(4, false, twoTokens = true) else null // `not in`
        T.PLUS, T.MINUS -> BinaryOp(5, false, false)
        T.STAR, T.SLASH -> BinaryOp(6, false, false)
        else -> null
    }

    private fun consumeOp(builder: PsiBuilder, op: BinaryOp) {
        builder.advanceLexer()
        if (op.twoTokens) builder.advanceLexer() // the `in` of `not in`
    }

    private fun isBlockMarker(type: IElementType?): Boolean =
        type == T.HEADING_MARKER || type == T.LIST_MARKER || type == T.ENUM_MARKER || type == T.TERM_MARKER

    private fun isProse(type: IElementType?): Boolean = type != null && type in PROSE_TOKENS

    private fun isCloser(type: IElementType?): Boolean =
        type == T.RPAREN || type == T.RBRACE || type == T.RBRACKET

    private fun isLiteral(type: IElementType?): Boolean = type != null && type in LITERAL_TOKENS

    private fun canStartExpr(type: IElementType?): Boolean = type != null && type in EXPR_STARTERS

    private companion object {
        /**
         * Recursion cap. Past this a construct consumes its opener as a leaf instead of descending, so
         * pathological nesting (`#(((…`, `[[[…`) yields a bounded tree rather than a StackOverflow.
         */
        private const val MAX_RECURSION_DEPTH = 256

        /** Inline prose leaves folded into a single [E.MARKUP] run. */
        private val PROSE_TOKENS: Set<IElementType> = setOf(
            T.TEXT, T.ESCAPE, T.SHORTHAND, T.SMART_QUOTE, T.LINEBREAK, T.LINK, T.LABEL_DEF,
        )

        /** Literal / atomic leaves that stand alone as a primary. */
        private val LITERAL_TOKENS: Set<IElementType> = setOf(
            T.INTEGER_LITERAL, T.FLOAT_LITERAL, T.NUMERIC_LITERAL,
            T.TRUE, T.FALSE, T.NONE, T.AUTO, T.UNDERSCORE,
        )

        /** Tokens that can begin a code expression (primary or unary prefix). */
        private val EXPR_STARTERS: Set<IElementType> = setOf(
            T.IDENTIFIER, T.STRING, T.RAW_TEXT,
            T.INTEGER_LITERAL, T.FLOAT_LITERAL, T.NUMERIC_LITERAL,
            T.TRUE, T.FALSE, T.NONE, T.AUTO, T.UNDERSCORE,
            T.LPAREN, T.LBRACE, T.LBRACKET, T.DOLLAR,
            T.MINUS, T.PLUS, T.KW_NOT,
            T.KW_LET, T.KW_SET, T.KW_SHOW, T.KW_CONTEXT, T.KW_IF, T.KW_WHILE, T.KW_FOR,
            T.KW_IMPORT, T.KW_INCLUDE, T.KW_RETURN, T.KW_BREAK, T.KW_CONTINUE,
        )
    }
}
