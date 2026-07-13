package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.references.TypstImportMemberReference

class TypstImportItem(node: ASTNode) : TypstPsiElement(node), PsiNameIdentifierOwner {

    val sourceName: String?
        get() = sourceNameNodes().joinToString(".") { it.text }.ifEmpty { null }

    val sourceSegments: List<String>
        get() = sourceNameNodes().map { it.text }

    val localAlias: String?
        get() = aliasNameNode()?.text

    override fun getNameIdentifier(): PsiElement? = aliasNameNode()?.psi ?: sourceNameNode()?.psi

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement {
        val source = sourceName ?: return this
        return replace(TypstElementFactory.createImportItem(project, source, name))
    }

    override fun getReference(): PsiReference? {
        val moduleImport = PsiTreeUtil.getParentOfType(this, TypstModuleImport::class.java) ?: return null
        return references.firstOrNull()
    }

    override fun getReferences(): Array<PsiReference> {
        val moduleImport = PsiTreeUtil.getParentOfType(this, TypstModuleImport::class.java) ?: return PsiReference.EMPTY_ARRAY
        if (sourceName == null) return PsiReference.EMPTY_ARRAY
        return sourceNameNodes().mapIndexed { sourceSegmentIndex, source ->
            val start = source.startOffset - node.startOffset
            TypstImportMemberReference(
                this,
                com.intellij.openapi.util.TextRange(start, start + source.textLength),
                moduleImport.pathString,
                sourceSegments,
                sourceSegmentIndex,
            )
        }.toTypedArray()
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    private fun sourceNameNode(): ASTNode? = sourceNameNodes().lastOrNull()

    private fun sourceNameNodes(): List<ASTNode> {
        val nodes = ArrayList<ASTNode>()
        var beforeAlias = true
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType == TypstTokenTypes.KW_AS) beforeAlias = false
            else if (beforeAlias && child.elementType == TypstTokenTypes.IDENTIFIER) nodes.add(child)
            child = child.treeNext
        }
        return nodes
    }

    private fun aliasNameNode(): ASTNode? {
        var sawAs = false
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType == TypstTokenTypes.KW_AS) sawAs = true
            else if (sawAs && child.elementType == TypstTokenTypes.IDENTIFIER) return child
            child = child.treeNext
        }
        return null
    }
}
