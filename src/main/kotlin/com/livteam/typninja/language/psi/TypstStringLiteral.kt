package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.tree.IElementType
import com.livteam.typninja.language.analysis.TypstModuleFiles

class TypstStringLiteral(node: ASTNode) : TypstPsiElement(node) {

    override fun getReferences(): Array<PsiReference> {
        val path = unquote(text)
        if (path.isEmpty() || path.startsWith("@") || path.startsWith("http://") || path.startsWith("https://")) {
            return PsiReference.EMPTY_ARRAY
        }
        val isModulePath = ancestor(TypstElementTypes.MODULE_IMPORT) != null ||
            ancestor(TypstElementTypes.MODULE_INCLUDE) != null
        if (!isModulePath && pathFunctionName() == null) return PsiReference.EMPTY_ARRAY
        val range = if (text.length >= 2) TextRange(1, text.length - 1) else TextRange(0, text.length)
        return arrayOf(TypstStringPathReference(this, range, path, isModulePath))
    }

    override fun getReference(): PsiReference? = references.firstOrNull()

    private fun pathFunctionName(): String? {
        if (ancestor(TypstElementTypes.ARGS) == null) return null
        val call = ancestor(TypstElementTypes.FUNC_CALL) ?: return null
        val name = firstIdentifierText(call) ?: return null
        return name.takeIf { it in pathFunctions }
    }

    private fun ancestor(type: IElementType): ASTNode? {
        var current: ASTNode? = node
        while (current != null) {
            if (current.elementType == type) return current
            current = current.treeParent
        }
        return null
    }

    private fun firstIdentifierText(node: ASTNode): String? {
        if (node.elementType == TypstTokenTypes.IDENTIFIER) return node.text
        var child = node.firstChildNode
        while (child != null) {
            firstIdentifierText(child)?.let { return it }
            child = child.treeNext
        }
        return null
    }

    private fun unquote(text: String): String =
        if (text.length >= 2 && text.first() == '"' && text.last() == '"') text.substring(1, text.length - 1) else text

    private val pathFunctions = setOf("image", "bibliography", "read", "csv", "json", "toml", "yaml", "xml", "cbor")
}

private class TypstStringPathReference(
    element: TypstStringLiteral,
    rangeInElement: TextRange,
    private val path: String,
    private val isModulePath: Boolean,
) : PsiReferenceBase<TypstStringLiteral>(element, rangeInElement) {

    override fun resolve(): PsiElement? {
        if (isModulePath) return TypstModuleFiles.resolveModuleFile(element.containingFile, path)
        val targetFile = element.containingFile.virtualFile?.parent?.findFileByRelativePath(path) ?: return null
        return PsiManager.getInstance(element.project).findFile(targetFile) as? PsiFile
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
