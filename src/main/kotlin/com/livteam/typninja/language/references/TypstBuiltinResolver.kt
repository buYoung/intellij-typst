package com.livteam.typninja.language.references

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.livteam.typninja.language.TypstLanguage
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.psi.TypstReferenceExpression

/**
 * Resolves a usage of a Typst standard-library builtin ([TypstBuiltins]) to a generated stub file so
 * Go To Declaration lands somewhere readable. Typst builtins are implemented in the compiler (there is
 * no `.typ` source), so the target is a project-cached synthetic `typst-std.typ` that declares each
 * builtin as a `#let` placeholder. This is a navigation aid, not the real implementation.
 *
 * Called only after file-local and cross-file import resolution both miss, so a user `#let text = …`
 * (shadowing the builtin `text`) always wins over the stub.
 */
object TypstBuiltinResolver {

    private val STUB_KEY: Key<CachedValue<PsiFile>> = Key.create("typst.builtin.stub.file")

    /** Resolve [name] to its stub `#let` declaration, or `null` when it is not a known builtin. */
    fun resolve(usage: TypstReferenceExpression, name: String): PsiElement? {
        if (!TypstBuiltins.isBuiltin(name)) return null
        val stub = stubFile(usage.project) as? TypstFile ?: return null
        return TypstModuleResolver.findExport(stub, name)
    }

    /** True when [element] lives in the generated builtin stub (used by highlighting to pick a color). */
    fun isStubElement(element: PsiElement?): Boolean {
        val file = element?.containingFile ?: return false
        return file.getUserData(STUB_MARKER) == true
    }

    private val STUB_MARKER: Key<Boolean> = Key.create("typst.builtin.stub.marker")

    private fun stubFile(project: Project): PsiFile? =
        CachedValuesManager.getManager(project).getCachedValue(project, STUB_KEY, {
            val file = PsiFileFactory.getInstance(project)
                .createFileFromText("typst-std.typ", TypstLanguage, buildStubText())
            file.putUserData(STUB_MARKER, true)
            CachedValueProvider.Result.create(file, ModificationTracker.NEVER_CHANGED)
        }, false)

    private fun buildStubText(): String = buildString {
        appendLine("// Typst standard library — generated navigation stubs.")
        appendLine("// Placeholders so Go To Declaration on a builtin lands here; the real")
        appendLine("// implementations live in the Typst compiler. Docs: https://typst.app/docs/reference/")
        appendLine()
        for (name in TypstBuiltins.FUNCTIONS) appendLine("#let $name = none")
        for (name in TypstBuiltins.TYPES) appendLine("#let $name = none")
        for (name in TypstBuiltins.MODULES) appendLine("#let $name = none")
    }
}
