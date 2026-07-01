package com.livteam.typninja.language.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.livteam.typninja.language.lexer.TypstLexerAdapter
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstFileElementType
import com.livteam.typninja.language.psi.TypstTokenTypes

/**
 * Wires the Typst language into the IntelliJ custom-language pipeline.
 *
 * Token bridging: [createLexer] returns a fresh [TypstLexerAdapter], whose generated `_TypstLexer`
 * emits the exact [TypstTokenTypes] instances. [TypstParser] navigates the [com.intellij.lang.PsiBuilder]
 * by comparing `getTokenType()` against those same [TypstTokenTypes] constants, so the parser
 * consumes precisely what the lexer produces (no second, mismatched token set is generated).
 *
 * Registered via `<lang.parserDefinition language="Typst" .../>`.
 */
class TypstParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = TypstLexerAdapter()

    override fun createParser(project: Project?): PsiParser = TypstParser()

    override fun getFileNodeType(): IFileElementType = TypstFileElementType

    override fun getWhitespaceTokens(): TokenSet = WHITESPACES

    override fun getCommentTokens(): TokenSet = COMMENTS

    override fun getStringLiteralElements(): TokenSet = STRINGS

    override fun createElement(node: ASTNode): PsiElement = TypstElementTypes.createElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = TypstFile(viewProvider)

    companion object {
        private val WHITESPACES: TokenSet = TokenSet.create(TokenType.WHITE_SPACE)
        private val COMMENTS: TokenSet =
            TokenSet.create(TypstTokenTypes.LINE_COMMENT, TypstTokenTypes.BLOCK_COMMENT)
        private val STRINGS: TokenSet = TokenSet.create(TypstTokenTypes.STRING)
    }
}
