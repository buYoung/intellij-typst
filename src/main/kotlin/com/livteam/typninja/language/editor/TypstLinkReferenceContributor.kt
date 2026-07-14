package com.livteam.typninja.language.editor

import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import com.intellij.patterns.PlatformPatterns
import com.intellij.openapi.paths.WebReference
import com.intellij.openapi.util.TextRange
import com.livteam.typninja.language.psi.TypstTokenTypes

class TypstLinkReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(TypstTokenTypes.LINK),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> =
                    if (element.text.startsWith("http://") || element.text.startsWith("https://")) {
                        arrayOf(WebReference(element, TextRange(0, element.textLength), element.text))
                    } else {
                        PsiReference.EMPTY_ARRAY
                    }
            },
        )
    }
}
