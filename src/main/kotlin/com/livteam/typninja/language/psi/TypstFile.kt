package com.livteam.typninja.language.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.livteam.typninja.language.TypstFileType
import com.livteam.typninja.language.TypstLanguage

/**
 * Root PSI file for a Typst document.
 *
 * Created by [com.livteam.typninja.language.parser.TypstParserDefinition.createFile] so that
 * opening a `.typ` file yields a Typst PSI tree (not a plain-text PSI). The actual children are
 * produced by [com.livteam.typninja.language.parser.TypstParser]; this class only ties the view
 * provider to [TypstLanguage] and reports the [TypstFileType].
 *
 * Holds no mutable state: all structure lives in the AST under the file node.
 */
class TypstFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, TypstLanguage) {

    override fun getFileType(): FileType = TypstFileType

    override fun toString(): String = "Typst File"
}
