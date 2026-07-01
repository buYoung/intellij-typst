package com.livteam.typninja.language.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.TokenType;
import com.livteam.typninja.language.psi.TypstTokenTypes;

/*
 * Typst MVP lexer (Typst 0.15.0 baseline).
 *
 * Lexical states:
 *   YYINITIAL      markup / document context (default)
 *   CODE           code context (entered by `#` from markup/math, and by code groups)
 *   MATH           math context (entered/exited by `$`)
 *   BLOCK_COMMENT  inside a block comment; supports nesting and unterminated-to-EOF
 *
 * Nesting awareness (groupStack):
 *   To classify multi-line bracketed groups correctly, the lexer keeps a small frame stack as a
 *   field. Each `(` / `{` / `[` (and the `#`-expression and `$`-math openers) pushes a frame that
 *   records the closer and the lexical state to resume on close. Consequences:
 *     - a content block `[ ... ]` opened from CODE lexes its body as MARKUP, so prose punctuation
 *       (`?` `!` `&` `@`) inside `#emph[...]` is TEXT, never BAD_CHARACTER (fixes M2);
 *     - a code group `( ... )` / `{ ... }` stays in CODE across newlines, so multi-line function
 *       calls / arrays / dictionaries / code blocks keep their code classification (fixes M3);
 *     - a bare `#`-expression with no open group is still line-bounded (Typst statement semantics).
 *
 * Restartability tradeoff (HONEST): groupStack and the block-comment depth are lexer FIELDS, not
 * encoded in the JFlex lexical-state int. A full forward pass (file open, full rehighlight, the
 * parser's full lex) is ALWAYS correct because it starts at offset 0 with an empty stack and rebuilds
 * the nesting as it goes. IntelliJ incremental relexing may restart mid-document from a stored
 * lexical-state int; the adapter (TypstLexerAdapter) clears these fields on every start(), so a
 * restart *inside* a multi-line group or block comment can briefly mis-classify until the next full
 * pass (documented over-relex, acceptable for the MVP). Progress and boundedness are never affected:
 * every rule still advances, BAD_CHARACTER stays one char, and no giant tokens are produced.
 *
 * Recovery: strings are bounded to a single line (a backslash escape can no longer cross a newline),
 * an unterminated block comment is a single comment token up to EOF, the CODE catch-all returns
 * BAD_CHARACTER one char at a time, and math recovers at a blank line -- so an unclosed construct
 * never collapses the rest of the file into one error / string token.
 */

%%

%public
%class _TypstLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType
%eof{  return;
%eof}

%state CODE
%state MATH
%state BLOCK_COMMENT

%{
  // ---- nesting frame stack (see file header for the restartability tradeoff) ----
  // A frame packs the resume state and a closer code: frame = (returnState << 4) | closerCode.
  // closerCode: 1 = ']'  2 = ')'  3 = '}'  4 = '#'-expression  5 = '$'-math.
  private final java.util.ArrayDeque<Integer> groupStack = new java.util.ArrayDeque<Integer>();

  private void pushFrame(int closerCode, int returnState) {
    groupStack.push((returnState << 4) | closerCode);
  }

  private int topCloser() {
    return groupStack.isEmpty() ? -1 : (groupStack.peek().intValue() & 0xF);
  }

  private int popReturnState() {
    return groupStack.isEmpty() ? YYINITIAL : (groupStack.pop().intValue() >> 4);
  }

  /** Close a structured group: if the top frame matches, pop it and resume its state. */
  private IElementType closeGroup(int closerCode, IElementType token) {
    if (topCloser() == closerCode) {
      yybegin(popReturnState());
    }
    return token;
  }

  // ---- block comment (nesting + unterminated) ----
  private int commentDepth = 0;
  private int commentStart = 0;
  private int commentReturnState = YYINITIAL;

  /**
   * Clears all field-held state. The adapter calls this on every start() so a full pass always
   * begins from an empty stack (see the restartability note in the file header).
   */
  public void resetState() {
    groupStack.clear();
    commentDepth = 0;
    commentStart = 0;
    commentReturnState = YYINITIAL;
  }
%}

//============================ macros ============================

LINE_COMMENT    = "//" [^\r\n]*

WHITE_SPACE     = [ \t\f]+
NEWLINE         = \R
BLANK_LINE      = \R [ \t\f]* \R

DIGITS          = [0-9]+
UNIT            = "pt" | "mm" | "cm" | "in" | "em" | "fr" | "deg" | "rad"
FLOAT_LITERAL   = ( {DIGITS}? "." {DIGITS} ( [eE] [+-]? {DIGITS} )? )
                | ( {DIGITS} [eE] [+-]? {DIGITS} )
                | ( {DIGITS} ( "." {DIGITS} )? ( {UNIT} | "%" ) )
INTEGER_LITERAL = {DIGITS}

ID_START        = [\p{L}_]
ID_CONTINUE     = [\p{L}\p{N}_\-]
IDENTIFIER      = {ID_START} {ID_CONTINUE}*

// A backslash escape may not cross a newline, so an unclosed string is bounded to its line.
STRING          = \" ( \\ [^\r\n] | [^\\\"\r\n] )* \"?
RAW_BLOCK       = "```" ~"```"
RAW_INLINE      = "`" [^`\r\n]* "`"?
ESCAPE          = \\ [^\r\n]

// Markup prose run: any run of chars that does not start one of the markup-significant tokens
// (whitespace, comment/raw/escape starters, mode markers, content brackets, reference sigil).
MARKUP_TEXT     = [^ \t\f\r\n$#\[\]@\\/`]+

%%

//===================== shared: line comment, block-comment entry & raw =====================
<YYINITIAL, CODE, MATH> {
  {LINE_COMMENT}    { return TypstTokenTypes.LINE_COMMENT; }
  "/*"              { commentDepth = 1;
                      commentStart = zzStartRead;
                      commentReturnState = yystate();
                      yybegin(BLOCK_COMMENT); }
  {RAW_BLOCK}       { return TypstTokenTypes.RAW_TEXT; }
  {RAW_INLINE}      { return TypstTokenTypes.RAW_TEXT; }
}

//============================ block comment (nesting + unterminated) ============================
// Content is consumed by silent (non-returning) actions; a single BLOCK_COMMENT token spanning the
// whole comment is emitted only when the outermost `*/` closes it or EOF is reached. `zzStartRead`
// is rewound to the comment start so the emitted token covers `[commentStart, current)`.
<BLOCK_COMMENT> {
  "/*"              { commentDepth++; }
  "*/"              { if (commentDepth > 0) { commentDepth--; }
                      if (commentDepth <= 0) {
                        yybegin(commentReturnState);
                        zzStartRead = commentStart;
                        return TypstTokenTypes.BLOCK_COMMENT;
                      } }
  <<EOF>>           { yybegin(commentReturnState);
                      zzStartRead = commentStart;
                      return TypstTokenTypes.BLOCK_COMMENT; }
  [^]               { }
}

//============================ MARKUP (default) ============================
<YYINITIAL> {
  "$"               { pushFrame(5, yystate()); yybegin(MATH); return TypstTokenTypes.DOLLAR; }
  "#"               { pushFrame(4, yystate()); yybegin(CODE); return TypstTokenTypes.HASH; }
  "["               { pushFrame(1, yystate()); return TypstTokenTypes.LBRACKET; }
  "]"               { return closeGroup(1, TypstTokenTypes.RBRACKET); }
  "@"               { return TypstTokenTypes.AT; }
  {ESCAPE}          { return TypstTokenTypes.ESCAPE; }

  {NEWLINE}         { return TokenType.WHITE_SPACE; }
  {WHITE_SPACE}     { return TokenType.WHITE_SPACE; }

  {MARKUP_TEXT}     { return TypstTokenTypes.TEXT; }
  "/"               { return TypstTokenTypes.TEXT; }   // lone slash (not // or /*)
  "\\"              { return TypstTokenTypes.TEXT; }   // backslash at end of line / EOF
}

//============================ shared: CODE & MATH expression tokens ============================
<CODE, MATH> {
  {STRING}          { return TypstTokenTypes.STRING; }
  {ESCAPE}          { return TypstTokenTypes.ESCAPE; }

  // keyword literals (must precede IDENTIFIER; equal-length match resolves to the rule listed first)
  "true"            { return TypstTokenTypes.TRUE; }
  "false"           { return TypstTokenTypes.FALSE; }
  "none"            { return TypstTokenTypes.NONE; }
  "auto"            { return TypstTokenTypes.AUTO; }

  // keywords
  "let"             { return TypstTokenTypes.KW_LET; }
  "set"             { return TypstTokenTypes.KW_SET; }
  "show"            { return TypstTokenTypes.KW_SHOW; }
  "context"         { return TypstTokenTypes.KW_CONTEXT; }
  "if"              { return TypstTokenTypes.KW_IF; }
  "else"            { return TypstTokenTypes.KW_ELSE; }
  "for"             { return TypstTokenTypes.KW_FOR; }
  "while"           { return TypstTokenTypes.KW_WHILE; }
  "in"              { return TypstTokenTypes.KW_IN; }
  "and"             { return TypstTokenTypes.KW_AND; }
  "or"              { return TypstTokenTypes.KW_OR; }
  "not"             { return TypstTokenTypes.KW_NOT; }
  "return"          { return TypstTokenTypes.KW_RETURN; }
  "import"          { return TypstTokenTypes.KW_IMPORT; }
  "include"         { return TypstTokenTypes.KW_INCLUDE; }
  "as"              { return TypstTokenTypes.KW_AS; }
  "break"           { return TypstTokenTypes.KW_BREAK; }
  "continue"        { return TypstTokenTypes.KW_CONTINUE; }

  {IDENTIFIER}      { return TypstTokenTypes.IDENTIFIER; }
  {FLOAT_LITERAL}   { return TypstTokenTypes.FLOAT_LITERAL; }
  {INTEGER_LITERAL} { return TypstTokenTypes.INTEGER_LITERAL; }

  // delimiters (nesting-aware: openers push a frame, closers pop the matching frame)
  "("               { pushFrame(2, yystate()); return TypstTokenTypes.LPAREN; }
  ")"               { return closeGroup(2, TypstTokenTypes.RPAREN); }
  "{"               { pushFrame(3, yystate()); return TypstTokenTypes.LBRACE; }
  "}"               { return closeGroup(3, TypstTokenTypes.RBRACE); }
  "["               { int cur = yystate();
                      pushFrame(1, cur);
                      if (cur == CODE) { yybegin(YYINITIAL); }  // content block body is MARKUP
                      return TypstTokenTypes.LBRACKET; }
  "]"               { return closeGroup(1, TypstTokenTypes.RBRACKET); }

  // operators (multi-char alternatives are longest-match, so order is for readability only)
  "=>"              { return TypstTokenTypes.ARROW; }
  "=="              { return TypstTokenTypes.EQ_EQ; }
  "!="              { return TypstTokenTypes.EXCL_EQ; }
  "<="              { return TypstTokenTypes.LT_EQ; }
  ">="              { return TypstTokenTypes.GT_EQ; }
  "+="              { return TypstTokenTypes.PLUS_EQ; }
  "-="              { return TypstTokenTypes.MINUS_EQ; }
  "*="              { return TypstTokenTypes.STAR_EQ; }
  "/="              { return TypstTokenTypes.SLASH_EQ; }
  ".."              { return TypstTokenTypes.DOT_DOT; }
  "+"               { return TypstTokenTypes.PLUS; }
  "-"               { return TypstTokenTypes.MINUS; }
  "*"               { return TypstTokenTypes.STAR; }
  "/"               { return TypstTokenTypes.SLASH; }
  "="               { return TypstTokenTypes.EQ; }
  "<"               { return TypstTokenTypes.LT; }
  ">"               { return TypstTokenTypes.GT; }
  "^"               { return TypstTokenTypes.HAT; }
  "."               { return TypstTokenTypes.DOT; }
  ","               { return TypstTokenTypes.COMMA; }
  ";"               { return TypstTokenTypes.SEMICOLON; }
  ":"               { return TypstTokenTypes.COLON; }

  {WHITE_SPACE}     { return TokenType.WHITE_SPACE; }
}

//============================ CODE only ============================
<CODE> {
  // `$` inside code: end a `#`-expression that lived in math (closing that math), otherwise open a
  // fresh math region nested in the current code.
  "$"               { int openFromState = CODE;
                      if (topCloser() == 4) { openFromState = popReturnState(); }
                      if (openFromState == MATH) {
                        if (topCloser() == 5) { yybegin(popReturnState()); } else { yybegin(YYINITIAL); }
                      } else {
                        pushFrame(5, openFromState);
                        yybegin(MATH);
                      }
                      return TypstTokenTypes.DOLLAR; }

  // End a bare `#`-expression at end of line; stay in CODE while inside a `(`/`{` group (multi-line).
  {NEWLINE}         { if (topCloser() == 2 || topCloser() == 3) { return TokenType.WHITE_SPACE; }
                      if (topCloser() == 4) { yybegin(popReturnState()); }
                      else { yybegin(YYINITIAL); }
                      return TokenType.WHITE_SPACE; }

  [^]               { return TokenType.BAD_CHARACTER; }
}

//============================ MATH only ============================
<MATH> {
  "$"               { if (topCloser() == 5) { yybegin(popReturnState()); } else { yybegin(YYINITIAL); }
                      return TypstTokenTypes.DOLLAR; }
  "#"               { pushFrame(4, yystate()); yybegin(CODE); return TypstTokenTypes.HASH; }
  // Math spans newlines; recover an unclosed math region at a blank line so it cannot pollute the
  // rest of the document.
  {BLANK_LINE}      { if (topCloser() == 5) { popReturnState(); } yybegin(YYINITIAL); return TokenType.WHITE_SPACE; }
  {NEWLINE}         { return TokenType.WHITE_SPACE; }
  // Remaining math symbols/text -> TEXT (avoids BAD_CHARACTER noise inside formulas).
  [^]               { return TypstTokenTypes.TEXT; }
}
