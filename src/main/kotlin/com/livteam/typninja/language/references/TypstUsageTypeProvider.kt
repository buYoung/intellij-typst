package com.livteam.typninja.language.references

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.livteam.typninja.language.psi.TypstFieldAccess
import com.livteam.typninja.language.psi.TypstImportItem
import com.livteam.typninja.language.psi.TypstMathIdentifier
import com.livteam.typninja.language.psi.TypstNamedArgument
import com.livteam.typninja.language.psi.TypstRef
import com.livteam.typninja.language.psi.TypstReferenceExpression

/** Adds meaningful groups while preserving IntelliJ's normal source-line usage rows. */
class TypstUsageTypeProvider : UsageTypeProvider {
    override fun getUsageType(element: PsiElement): UsageType? = when {
        PsiTreeUtil.getParentOfType(element, TypstImportItem::class.java, false) != null -> IMPORT
        PsiTreeUtil.getParentOfType(element, TypstNamedArgument::class.java, false) != null -> NAMED_ARGUMENT
        PsiTreeUtil.getParentOfType(element, TypstFieldAccess::class.java, false) != null -> FIELD_ACCESS
        PsiTreeUtil.getParentOfType(element, TypstRef::class.java, false) != null -> LABEL_REFERENCE
        PsiTreeUtil.getParentOfType(element, TypstMathIdentifier::class.java, false) != null -> MATH
        PsiTreeUtil.getParentOfType(element, TypstReferenceExpression::class.java, false) != null -> UsageType.READ
        else -> null
    }

    private companion object {
        val IMPORT = UsageType("Typst import")
        val NAMED_ARGUMENT = UsageType("Typst named argument")
        val FIELD_ACCESS = UsageType("Typst field access")
        val LABEL_REFERENCE = UsageType("Typst label reference")
        val MATH = UsageType("Typst math")
    }
}
