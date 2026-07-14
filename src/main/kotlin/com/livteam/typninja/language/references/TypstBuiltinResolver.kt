package com.livteam.typninja.language.references

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
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

    /** Resolve [name] to its stub `#let` declaration, or `null` when it is not a known builtin. */
    fun resolve(usage: PsiElement, name: String): PsiElement? {
        return resolve(usage.project, name)
    }

    fun resolve(project: Project, name: String): PsiElement? {
        if (!TypstBuiltins.isBuiltin(name) && TypstBuiltins.allStubNames().contains(name).not()) return null
        val stub = TypstBuiltinStubService.getInstance(project).stubFile() as? TypstFile ?: return null
        return TypstModuleResolver.findExport(stub, name)
    }

    /** True when [element] lives in the generated builtin stub (used by highlighting to pick a color). */
    fun isStubElement(element: PsiElement?): Boolean {
        val file = element?.containingFile ?: return false
        return file.getUserData(STUB_MARKER) == true
    }

    internal val STUB_MARKER: Key<Boolean> = Key.create("typst.builtin.stub.marker")

    internal fun buildStubText(): String = buildString {
        appendLine("// Typst standard library — generated navigation stubs.")
        appendLine("// Placeholders so Go To Declaration on a builtin lands here; the real")
        appendLine("// implementations live in the Typst compiler. Docs: https://typst.app/docs/reference/")
        appendLine()
        for (name in TypstBuiltins.allStubNames()) {
            val metadata = TypstBuiltins.stubMetadata(name)
            if (metadata?.kind == TypstBuiltins.Kind.FUNCTION) {
                val parameters = metadata.parameters.ifEmpty { listOf(TypstBuiltins.Parameter("arguments")) }
                append("#let ").append(name).append('(')
                append(parameters.joinToString { "${it.name}: none" })
                appendLine(") = none")
            } else {
                appendLine("#let $name = none")
            }
        }
    }
}

/** Owns synthetic builtin PSI so project user data cannot retain plugin classes after unload. */
@Service(Service.Level.PROJECT)
class TypstBuiltinStubService(private val project: Project) : Disposable {
    @Volatile
    private var cachedStub: PsiFile? = null

    fun stubFile(): PsiFile = cachedStub ?: synchronized(this) {
        cachedStub ?: PsiFileFactory.getInstance(project)
            .createFileFromText("typst-std.typ", TypstLanguage, TypstBuiltinResolver.buildStubText())
            .also { file ->
                file.putUserData(TypstBuiltinResolver.STUB_MARKER, true)
                cachedStub = file
            }
    }

    override fun dispose() {
        cachedStub = null
    }

    companion object {
        fun getInstance(project: Project): TypstBuiltinStubService = project.service()
    }
}
