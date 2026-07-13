package com.livteam.typninja.language.references

import com.intellij.psi.PsiElement
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstResolveResult
import com.livteam.typninja.language.psi.TypstReferenceExpression

/**
 * Resolution facade for code-context identifier usages.
 *
 * The shared analysis layer owns declaration/import collection and shadowing-aware resolution. This
 * object preserves the old call surface for PSI references and existing callers.
 */
object TypstReferenceResolver {

    fun resolveResult(usage: TypstReferenceExpression): TypstResolveResult? =
        TypstAnalysis.resolve(usage)

    fun resolve(usage: TypstReferenceExpression): PsiElement? =
        resolveResult(usage)?.definition?.navigationElement
}
