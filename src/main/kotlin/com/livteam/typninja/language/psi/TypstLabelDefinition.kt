package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner

class TypstLabelDefinition(node: ASTNode) : TypstPsiElement(node), PsiNameIdentifierOwner {

    override fun getNameIdentifier(): PsiElement? = node.findChildByType(TypstTokenTypes.LABEL_DEF)?.psi

    override fun getName(): String? = nameIdentifier?.text?.removePrefix("<")?.removeSuffix(">")

    override fun setName(name: String): PsiElement =
        replace(TypstElementFactory.createLabel(project, name))

    override fun getTextOffset(): Int = nameIdentifier?.textOffset?.plus(1) ?: super.getTextOffset()
}
