package com.livteam.typninja.runtime

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

internal data class TypstWasmCompilation(
    val output: JsonObject,
    val binary: List<ByteArray>,
    val workspace: TypstWasmWorkspace,
)

/** Supplies only files requested by Typst, including unsaved editor documents. */
internal class TypstWasmWorkspace(
    private val project: Project,
    val root: Path,
    private val overlays: Map<Path, String>,
    private val onPackage: (String, TypstPackageStatus) -> Unit,
) {
    val sources = HashMap<Path, String>()
    private val packageDirectories = HashMap<String, Path>()

    fun compile(runtime: TypstWasmRuntime, main: Path, options: TypstWasmOptions): TypstWasmCompilation {
        require(main.startsWith(root)) { "The main file must be inside the configured Typst root" }
        runtime.request(mapOf("method" to "resetFiles"))
        runtime.request(mapOf("method" to "setInputs", "inputs" to options.inputs))
        val attempted = HashSet<String>()
        load(runtime, virtualPath(main), null, attempted)
        val clock = OffsetDateTime.now()
        val command = mapOf("method" to "compile", "path" to virtualPath(main), "timestampMillis" to clock.toInstant().toEpochMilli(),
            "utcOffsetMinutes" to clock.offset.totalSeconds / 60, "options" to options.values)
        while (true) {
            TypstWasmRuntime.checkCancellation()
            val result = runtime.request(command).asJsonObject
            var loaded = false
            for (item in result.getAsJsonArray("missingFiles")) {
                val file = item.asJsonObject
                val specification = file["package"]?.takeUnless { it.isJsonNull }?.asString
                if (load(runtime, file["path"].asString, specification, attempted)) loaded = true
            }
            if (loaded) continue
            val binary = (0 until result["binaryOutputCount"].asInt).map(runtime::takeOutput)
            return TypstWasmCompilation(result, binary, this)
        }
    }

    fun virtualPath(path: Path): String {
        require(path.startsWith(root)) { "The file must be inside the configured Typst root" }
        return "/" + root.relativize(path).joinToString("/")
    }

    fun resolve(displayPath: String): Path? {
        if (!displayPath.startsWith('@')) return safePath(root, displayPath)
        val versionSeparator = displayPath.indexOf(':')
        if (versionSeparator < 0) return null
        val separator = displayPath.indexOf('/', versionSeparator + 1)
        if (separator < 0) return null
        val specification = displayPath.substring(0, separator)
        val directory = packageDirectories[specification] ?: TypstPackageStorage.find(project, specification) ?: return null
        return safePath(directory, displayPath.substring(separator))
    }

    private fun load(runtime: TypstWasmRuntime, path: String, specification: String?, attempted: MutableSet<String>): Boolean {
        if (!attempted.add("${specification.orEmpty()}:$path")) return false
        val directory = if (specification == null) root else packageDirectories.getOrPut(specification) {
            TypstPackageStorage.find(project, specification) ?: run {
                onPackage(specification, TypstPackageStatus.DOWNLOADING)
                val installed = TypstPackageStorage.ensure(project, specification)
                onPackage(specification, if (installed == null) TypstPackageStatus.FAILED else TypstPackageStatus.INSTALLED)
                installed ?: return false
            }
        }
        val file = safePath(directory, path) ?: return false
        val text = overlays[file]
        val bytes = text?.toByteArray(Charsets.UTF_8) ?: run {
            if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(directory.toRealPath())) return false
            Files.readAllBytes(file)
        }
        runtime.request(mapOf("method" to "setFile", "path" to path, "package" to specification), bytes)
        if (file.fileName.toString().endsWith(".typ")) sources[file] = text ?: bytes.toString(Charsets.UTF_8)
        return true
    }

    private fun safePath(directory: Path, virtual: String): Path? {
        if ('\\' in virtual || ':' in virtual || virtual.split('/').any { it == ".." }) return null
        val path = directory.resolve(virtual.trimStart('/')).normalize()
        return path.takeIf { it.startsWith(directory) }
    }
}
