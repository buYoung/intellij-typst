package com.livteam.typninja.language.highlighting

import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Factory that provides a fresh [TypstSyntaxHighlighter] instance for every request.
 *
 * Registered in plugin.xml:
 * ```xml
 * <lang.syntaxHighlighterFactory language="Typst"
 *     implementationClass="com.livteam.typninja.language.highlighting.TypstSyntaxHighlighterFactory"/>
 * ```
 */
class TypstSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        TypstSyntaxHighlighter()
}
