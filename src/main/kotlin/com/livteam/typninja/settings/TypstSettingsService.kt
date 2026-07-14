package com.livteam.typninja.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
@State(name = "TypstProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TypstSettingsService(private val project: Project) : SimplePersistentStateComponent<TypstSettingsService.Settings>(Settings()) {

    class Settings : BaseState() {
        var useDefaultPackageRoots by property(true)
        var packagePath by string("")
        var packageCachePath by string("")
        var rootPath by string("")
        var mainFilePath by string("")
        var enableSemanticDiagnostics by property(true)
        var enableSemanticHighlighting by property(true)
        var enableLint by property(true)
        var syntaxOnlyMode by property(false)
        var showPackageVersionHints by property(true)
        var enablePostfixCompletion by property(true)
        var enableUfcsCompletion by property(true)
        var enableUfcsLeftCompletion by property(true)
        var enableUfcsRightCompletion by property(true)
        var typstExecutablePath by string("")
        var useSystemFonts by property(true)
        var fontPaths by string("")
        var typstExtraArguments by string("")
        var previewPpi by property(144)
        var autoPreview by string("onSave")
        var previewArguments by string("")
        var invertPreviewColors by property(false)
        var defaultExportFormat by string("pdf")
        var autoExport by string("never")
        var exportTarget by string("paged")
        var outputPathPattern by string("")
        var enableOnEnter by property(true)
        var enableResourcePaste by property(true)
        var resourcePasteFolder by string("\$root/assets")
    }

    fun packageRoots(): List<String> = buildList {
        state.packagePath?.takeIf { it.isNotBlank() }?.let(::add)
        state.packageCachePath?.takeIf { it.isNotBlank() }?.let(::add)
    }

    fun workspaceRoot(sourcePath: Path? = null): Path {
        resolveProjectPath(state.rootPath.orEmpty())?.let { return it.normalize() }
        val projectRoot = project.basePath?.let(Paths::get)?.toAbsolutePath()?.normalize()
        if (projectRoot != null && (sourcePath == null || sourcePath.toAbsolutePath().normalize().startsWith(projectRoot))) {
            return projectRoot
        }
        return sourcePath?.toAbsolutePath()?.normalize()?.parent ?: projectRoot ?: Paths.get(".").toAbsolutePath().normalize()
    }

    fun mainFile(sourcePath: Path? = null): Path {
        val root = workspaceRoot(sourcePath)
        val configured = state.mainFilePath.orEmpty().trim()
        if (configured.isNotEmpty()) {
            val path = Paths.get(expandWorkspaceVariable(configured))
            return (if (path.isAbsolute) path else root.resolve(path)).normalize()
        }
        return sourcePath?.toAbsolutePath()?.normalize() ?: root.resolve("main.typ")
    }

    fun resolvedFontPaths(sourcePath: Path? = null): List<Path> {
        val root = workspaceRoot(sourcePath)
        return splitPathList(state.fontPaths.orEmpty()).mapNotNull { configured ->
            runCatching {
                val path = Paths.get(expandWorkspaceVariable(configured))
                (if (path.isAbsolute) path else root.resolve(path)).normalize()
            }.getOrNull()
        }.distinct()
    }

    fun extraArguments(): List<String> = parseArguments(state.typstExtraArguments.orEmpty())

    fun previewArguments(): List<String> = parseArguments(state.previewArguments.orEmpty())

    private fun resolveProjectPath(configured: String): Path? {
        if (configured.isBlank()) return null
        return runCatching {
            val expanded = Paths.get(expandWorkspaceVariable(configured.trim()))
            if (expanded.isAbsolute) expanded else project.basePath?.let(Paths::get)?.resolve(expanded) ?: expanded.toAbsolutePath()
        }.getOrNull()
    }

    private fun expandWorkspaceVariable(value: String): String =
        value.replace("${'$'}{workspaceFolder}", project.basePath.orEmpty())

    private fun splitPathList(value: String): List<String> =
        value.lineSequence().flatMap { it.split(';').asSequence() }.map(String::trim).filter(String::isNotEmpty).toList()

    private fun parseArguments(value: String): List<String> {
        val arguments = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        fun finishArgument() {
            if (current.isNotEmpty()) {
                arguments.add(current.toString())
                current.setLength(0)
            }
        }
        for (character in value) {
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }
                character == '\\' -> escaped = true
                quote != null && character == quote -> quote = null
                quote == null && (character == '\'' || character == '"') -> quote = character
                quote == null && character.isWhitespace() -> finishArgument()
                else -> current.append(character)
            }
        }
        if (escaped) current.append('\\')
        finishArgument()
        return arguments
    }

    companion object {
        fun getInstance(project: Project): TypstSettingsService = project.service()
    }
}
