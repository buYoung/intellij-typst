package com.livteam.typninja.language.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.references.TypstMathReference

/** A name written inside `$ ... $`, including one segment of a dotted math symbol. */
class TypstMathIdentifier(node: ASTNode) : TypstPsiElement(node) {

    val referenceName: String get() = node.findChildByType(TypstTokenTypes.MATH_IDENT)?.text ?: text

    val qualifiedPath: String
        get() {
            val segments = ArrayDeque<String>()
            segments.addFirst(referenceName)
            var previous = PsiTreeUtil.prevLeaf(firstChild)
            while (previous?.text == ".") {
                val nameLeaf = PsiTreeUtil.prevLeaf(previous) ?: break
                if (nameLeaf.node.elementType != TypstTokenTypes.MATH_IDENT) break
                segments.addFirst(nameLeaf.text)
                previous = PsiTreeUtil.prevLeaf(nameLeaf)
            }
            return segments.joinToString(".")
        }

    override fun getReference(): PsiReference? =
        if (TypstAnalysis.resolveMath(this) == null) null else TypstMathReference(this)

    override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}
