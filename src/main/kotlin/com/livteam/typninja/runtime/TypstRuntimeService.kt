package com.livteam.typninja.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.livteam.typninja.execution.TypstToolchainService
import com.livteam.typninja.language.analysis.TypstProjectModelService
import com.livteam.typninja.language.references.TypstPackageResolver
import com.livteam.typninja.language.TypstFileType
import com.livteam.typninja.settings.TypstSettingsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class TypstRuntimeService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private val logger = Logger.getInstance(TypstRuntimeService::class.java)
    private val gson = Gson()
    private val installer = TypstRuntimeInstaller()
    private val lifecycleMutex = Mutex()
    private val packageIndexMutex = Mutex()
    private val requestSequence = AtomicLong()
    private val generation = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val diagnosticsByUri = ConcurrentHashMap<String, List<TypstRuntimeDiagnostic>>()
    private val packageStatuses = ConcurrentHashMap<String, TypstPackageStatus>()
    private val overlayUris = ConcurrentHashMap.newKeySet<String>()
    private val intentionalProcessStops = ConcurrentHashMap.newKeySet<Long>()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private var initializedFingerprint: String? = null
    private var restartCount = 0
    private var packageIndexFetchedAtMillis = 0L

    @Volatile
    var status: TypstRuntimeStatus = TypstRuntimeStatus.UNINSTALLED
        private set

    fun diagnosticsFor(file: VirtualFile): List<TypstRuntimeDiagnostic> =
        diagnosticsByUri[file.url].orEmpty()

    fun packageStatus(specification: String): TypstPackageStatus? = packageStatuses[specification]

    fun requestCompile(source: VirtualFile, unsavedText: String?, render: Boolean, documentVersion: Long = source.modificationStamp): Job =
        coroutineScope.launch {
            compile(source, unsavedText, render, documentVersion)
        }

    fun requestDocumentToSource(
        source: VirtualFile,
        documentVersion: Long,
        runtimeGeneration: Long,
        page: Int,
        x: Double,
        y: Double,
        onMapped: (TypstRuntimeSourcePosition) -> Unit,
    ): Job = coroutineScope.launch {
        try {
            val response = request(
                source,
                documentVersion,
                runtimeGeneration,
                "documentToSource",
                mapOf("page" to page, "x" to x, "y" to y),
            )
            if (response.get("generation")?.asLong != runtimeGeneration ||
                response.get("documentVersion")?.asLong != documentVersion
            ) return@launch
            val result = response.getAsJsonObject("result") ?: return@launch
            if (result.get("mapped")?.asBoolean != true) return@launch
            val position = TypstRuntimeSourcePosition(
                uri = result.get("uri")?.asString ?: return@launch,
                line = result.get("line")?.asInt ?: return@launch,
                column = result.get("column")?.asInt ?: return@launch,
                endLine = result.get("endLine")?.asInt ?: result.get("line")?.asInt ?: return@launch,
                endColumn = result.get("endColumn")?.asInt ?: result.get("column")?.asInt ?: return@launch,
            )
            withContext(Dispatchers.EDT) { onMapped(position) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Failed to map Typst preview position to source", exception)
        }
    }

    fun requestSourceToDocument(
        source: VirtualFile,
        documentVersion: Long,
        runtimeGeneration: Long,
        position: TypstRuntimeSourcePosition,
        onMapped: (List<TypstRuntimeDocumentPosition>) -> Unit,
    ): Job = coroutineScope.launch {
        try {
            val response = request(
                source,
                documentVersion,
                runtimeGeneration,
                "sourceToDocument",
                mapOf("uri" to position.uri, "line" to position.line, "column" to position.column),
            )
            if (response.get("generation")?.asLong != runtimeGeneration ||
                response.get("documentVersion")?.asLong != documentVersion
            ) return@launch
            val result = response.getAsJsonObject("result") ?: return@launch
            if (result.get("mapped")?.asBoolean != true) return@launch
            val positions = result.getAsJsonArray("positions")?.map {
                gson.fromJson(it, TypstRuntimeDocumentPosition::class.java)
            }.orEmpty()
            if (positions.isEmpty()) return@launch
            withContext(Dispatchers.EDT) { onMapped(positions) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Failed to map Typst source position to preview", exception)
        }
    }

    suspend fun compile(
        source: VirtualFile,
        unsavedText: String?,
        render: Boolean,
        documentVersion: Long = source.modificationStamp,
    ): TypstRuntimeCompileResult? {
        val settings = TypstSettingsService.getInstance(project)
        if (!settings.state.useNativeRenderer || settings.extraArguments().isNotEmpty() ||
            settings.previewArguments().isNotEmpty()) {
            status = TypstRuntimeStatus.CLI_FALLBACK
            return if (render) null else compileCliDiagnostics(source, documentVersion)
        }
        val capability = TypstToolchainService.getInstance(project).currentCapability()
        val executable = capability.executablePath ?: run {
            status = TypstRuntimeStatus.CLI_FALLBACK
            return null
        }
        return try {
            if (ensureInitialized(source, executable) == null) {
                return if (render) null else compileCliDiagnostics(source, documentVersion)
            }
            synchronizeSourceOverlays(source, unsavedText, documentVersion)
            val compileGeneration = generation.incrementAndGet()
            val response = request(
                source,
                documentVersion,
                compileGeneration,
                "compile",
                mapOf("render" to render),
            )
            if (response.get("generation")?.asLong != compileGeneration ||
                response.get("documentVersion")?.asLong != documentVersion
            ) return null
            val result = response.getAsJsonObject("result") ?: return null
            val diagnostics = result.getAsJsonArray("diagnostics")?.map {
                gson.fromJson(it, TypstRuntimeDiagnostic::class.java)
            }.orEmpty()
            diagnostics.groupBy(TypstRuntimeDiagnostic::uri).forEach(diagnosticsByUri::put)
            if (diagnostics.none { it.uri == source.url }) diagnosticsByUri.remove(source.url)
            withContext(Dispatchers.EDT) { DaemonCodeAnalyzer.getInstance(project).restart() }
            TypstRuntimeCompileResult(
                generation = compileGeneration,
                documentVersion = documentVersion,
                outputStatus = result.get("outputStatus")?.asString ?: "failed",
                diagnostics = diagnostics,
                pages = result.getAsJsonArray("pages")?.map {
                    gson.fromJson(it, TypstRuntimePage::class.java)
                }.orEmpty(),
                sourceMappingAvailable = result.get("sourceMappingAvailable")?.asBoolean == true,
                previewUrl = result.get("previewUrl")?.takeUnless { it.isJsonNull }?.asString,
            )
        } catch (exception: CancellationException) {
            stopRuntimeForCancellation()
            throw exception
        } catch (exception: Exception) {
            logger.warn("Typst runtime compile failed; falling back to the Typst CLI", exception)
            status = TypstRuntimeStatus.CLI_FALLBACK
            if (render) null else compileCliDiagnostics(source, documentVersion)
        }
    }

    private suspend fun synchronizeSourceOverlays(
        source: VirtualFile,
        explicitText: String?,
        documentVersion: Long,
    ) {
        val settings = TypstSettingsService.getInstance(project)
        val root = settings.workspaceRoot(Path.of(source.path))
        val overlays = readAction {
            buildMap<String, String> {
                FileDocumentManager.getInstance().unsavedDocuments.forEach { document ->
                    val file = FileDocumentManager.getInstance().getFile(document) ?: return@forEach
                    if (file.fileType == TypstFileType &&
                        runCatching { Path.of(file.path).toAbsolutePath().normalize().startsWith(root) }.getOrDefault(false)
                    ) {
                        put(file.url, document.text)
                    }
                }
                if (explicitText != null) put(source.url, explicitText)
            }
        }
        val staleUris = overlayUris.filterNot(overlays::containsKey)
        for (uri in staleUris) {
            request(source, documentVersion, generation.get(), "updateSource", mapOf("uri" to uri, "text" to null))
            overlayUris.remove(uri)
        }
        for ((uri, text) in overlays) {
            request(source, documentVersion, generation.get(), "updateSource", mapOf("uri" to uri, "text" to text))
            overlayUris.add(uri)
        }
    }

    private suspend fun compileCliDiagnostics(
        source: VirtualFile,
        documentVersion: Long,
    ): TypstRuntimeCompileResult? {
        val settings = TypstSettingsService.getInstance(project)
        val capability = TypstToolchainService.getInstance(project).currentCapability()
        val executable = capability.executablePath ?: return null
        val sourcePath = Path.of(source.path).toAbsolutePath().normalize()
        val root = settings.workspaceRoot(sourcePath)
        val main = settings.mainFile(sourcePath)
        val output = withContext(Dispatchers.IO) {
            val temporary = java.nio.file.Files.createTempFile("typst-diagnostics-", ".pdf")
            try {
                val command = GeneralCommandLine(executable)
                    .withWorkDirectory(root.toFile())
                    .withParameters("compile", "--diagnostic-format", "short")
                command.addParameters(settings.extraArguments())
                command.addParameters("--root", root.toString())
                settings.resolvedFontPaths(sourcePath).forEach { command.addParameters("--font-path", it.toString()) }
                if (!settings.state.useSystemFonts) command.addParameter("--ignore-system-fonts")
                settings.state.packagePath.orEmpty().takeIf(String::isNotBlank)
                    ?.let { command.addParameters("--package-path", it) }
                settings.state.packageCachePath.orEmpty().takeIf(String::isNotBlank)
                    ?.let { command.addParameters("--package-cache-path", it) }
                command.addParameters(main.toString(), temporary.toString())
                val process = command.createProcess()
                val error = process.errorStream.bufferedReader().readText()
                process.inputStream.close()
                process.waitFor()
                process.exitValue() to error
            } finally {
                java.nio.file.Files.deleteIfExists(temporary)
            }
        }
        val diagnostics = parseCliDiagnostics(output.second, root)
        diagnostics.groupBy(TypstRuntimeDiagnostic::uri).forEach(diagnosticsByUri::put)
        if (diagnostics.none { it.uri == source.url }) diagnosticsByUri.remove(source.url)
        withContext(Dispatchers.EDT) { DaemonCodeAnalyzer.getInstance(project).restart() }
        return TypstRuntimeCompileResult(
            generation = generation.incrementAndGet(),
            documentVersion = documentVersion,
            outputStatus = if (output.first == 0) "success" else "failed",
            diagnostics = diagnostics,
            pages = emptyList(),
            sourceMappingAvailable = false,
            previewUrl = null,
        )
    }

    private fun parseCliDiagnostics(output: String, root: Path): List<TypstRuntimeDiagnostic> =
        output.lineSequence().mapNotNull { line ->
            val match = CLI_DIAGNOSTIC_PATTERN.matchEntire(line.trim()) ?: return@mapNotNull null
            val path = runCatching {
                val parsed = Path.of(match.groupValues[1])
                (if (parsed.isAbsolute) parsed else root.resolve(parsed)).normalize()
            }.getOrNull() ?: return@mapNotNull null
            val messageWithSeverity = match.groupValues[4]
            val severity = if (messageWithSeverity.startsWith("warning:")) "warning" else "error"
            val message = messageWithSeverity.substringAfter(':', messageWithSeverity).trim()
            val lineIndex = match.groupValues[2].toInt().coerceAtLeast(1) - 1
            val columnIndex = match.groupValues[3].toInt().coerceAtLeast(1) - 1
            TypstRuntimeDiagnostic(
                severity = severity,
                message = message,
                uri = path.toUri().toString(),
                startLine = lineIndex,
                startColumn = columnIndex,
                endLine = lineIndex,
                endColumn = columnIndex + 1,
            )
        }.toList()

    fun ensurePreviewPackage(specification: String) {
        val parsed = TypstPackageResolver.parse(specification) ?: return
        if (parsed.namespace != "preview") return
        if (!TypstSettingsService.getInstance(project).state.autoDownloadPackages) return
        if (TypstPackageResolver.resolveEntrypoint(project, specification) != null) {
            packageStatuses[specification] = TypstPackageStatus.INSTALLED
            return
        }
        packageStatuses.putIfAbsent(specification, TypstPackageStatus.AVAILABLE_REMOTELY)
        if (packageStatuses.replace(
                specification,
                TypstPackageStatus.AVAILABLE_REMOTELY,
                TypstPackageStatus.DOWNLOADING,
            ).not()
        ) return
        coroutineScope.launch {
            val source = project.basePath?.let { VirtualFileManager.getInstance().findFileByNioPath(Path.of(it)) }
            if (source == null) {
                packageStatuses[specification] = TypstPackageStatus.FAILED
                return@launch
            }
            try {
                ensureInitialized(source, TypstToolchainService.getInstance(project).currentCapability().executablePath ?: return@launch)
                    ?: return@launch
                refreshPackageIndexIfExpired(source)
                request(
                    source,
                    0,
                    generation.get(),
                    "ensurePackage",
                    mapOf(
                        "specification" to specification,
                        "archiveUrl" to "https://packages.typst.org/preview/${parsed.name}-${parsed.version}.tar.gz",
                        "sha256" to null,
                        "maxBytes" to MAX_PACKAGE_BYTES,
                    ),
                )
                packageStatuses[specification] = TypstPackageStatus.INSTALLED
                withContext(Dispatchers.IO) {
                    TypstProjectModelService.getInstance(project).requestRefresh()
                    VirtualFileManager.getInstance().asyncRefresh(null)
                }
                withContext(Dispatchers.EDT) { DaemonCodeAnalyzer.getInstance(project).restart() }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn("Failed to install Typst package $specification", exception)
                packageStatuses[specification] = TypstPackageStatus.FAILED
                withContext(Dispatchers.EDT) { DaemonCodeAnalyzer.getInstance(project).restart() }
            }
        }
    }

    private suspend fun refreshPackageIndexIfExpired(source: VirtualFile) = packageIndexMutex.withLock {
        val now = System.currentTimeMillis()
        val cache = Path.of(PathManager.getSystemPath(), "typst", "package-index.json")
        val diskCacheIsFresh = withContext(Dispatchers.IO) {
            java.nio.file.Files.isRegularFile(cache) &&
                now - java.nio.file.Files.getLastModifiedTime(cache).toMillis() < PACKAGE_INDEX_TTL_MILLIS
        }
        if (diskCacheIsFresh || now - packageIndexFetchedAtMillis < PACKAGE_INDEX_TTL_MILLIS) return@withLock
        val response = request(
            source,
            0,
            generation.get(),
            "packageIndex",
            mapOf(
                "indexUrl" to PACKAGE_INDEX_URL,
                "maxBytes" to MAX_PACKAGE_INDEX_BYTES,
            ),
        )
        val index = response.getAsJsonObject("result")?.get("index")?.asString
            ?: throw IllegalStateException("Typst package index response is empty")
        withContext(Dispatchers.IO) {
            java.nio.file.Files.createDirectories(cache.parent)
            val temporary = java.nio.file.Files.createTempFile(cache.parent, "package-index-", ".json")
            try {
                java.nio.file.Files.writeString(temporary, index)
                runCatching {
                    java.nio.file.Files.move(
                        temporary,
                        cache,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }.getOrElse {
                    java.nio.file.Files.move(temporary, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                java.nio.file.Files.deleteIfExists(temporary)
            }
        }
        packageIndexFetchedAtMillis = now
    }

    private suspend fun ensureInitialized(source: VirtualFile, typstExecutable: String): Process? = lifecycleMutex.withLock {
        val settings = TypstSettingsService.getInstance(project)
        val executable = if (process?.isAlive == true) null else installer.resolve(settings.state.autoDownloadRenderer, ::setStatus)
        if (process?.isAlive != true) {
            if (restartCount >= MAX_RESTARTS) {
                status = TypstRuntimeStatus.CLI_FALLBACK
                return@withLock null
            }
            if (executable == null) {
                status = TypstRuntimeStatus.CLI_FALLBACK
                return@withLock null
            }
            startProcess(executable)
        }
        val sourcePath = Path.of(source.path).toAbsolutePath().normalize()
        val root = settings.workspaceRoot(sourcePath)
        val main = settings.mainFile(sourcePath)
        val fingerprint = listOf(
            root, main, typstExecutable, settings.state.useSystemFonts,
            settings.state.packagePath, settings.state.packageCachePath, settings.state.fontPaths,
        ).joinToString("|")
        if (initializedFingerprint != fingerprint) {
            request(
                source,
                source.modificationStamp,
                generation.get(),
                "initialize",
                mapOf(
                    "rootUri" to root.toUri().toString(),
                    "mainUri" to main.toUri().toString(),
                    "typstExecutable" to typstExecutable,
                    "fontPaths" to settings.resolvedFontPaths(sourcePath).map(Path::toString),
                    "useSystemFonts" to settings.state.useSystemFonts,
                    "packagePath" to settings.state.packagePath,
                    "packageCachePath" to settings.state.packageCachePath,
                ),
            )
            initializedFingerprint = fingerprint
        }
        process
    }

    private fun startProcess(executable: Path) {
        val child = ProcessBuilder(executable.toString()).start()
        process = child
        writer = child.outputWriter()
        status = TypstRuntimeStatus.READY
        readerJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                child.inputReader().useLines { lines -> lines.forEach(::acceptResponse) }
            } finally {
                val wasIntentional = intentionalProcessStops.remove(child.pid())
                if (process === child) {
                    if (!wasIntentional) restartCount++
                    process = null
                    writer = null
                    initializedFingerprint = null
                    val failure = IllegalStateException("Typst runtime exited with code ${runCatching { child.exitValue() }.getOrNull()}")
                    pending.values.forEach { it.completeExceptionally(failure) }
                    pending.clear()
                    status = when {
                        restartCount >= MAX_RESTARTS -> TypstRuntimeStatus.CLI_FALLBACK
                        wasIntentional -> TypstRuntimeStatus.UNINSTALLED
                        else -> TypstRuntimeStatus.FAILED
                    }
                }
            }
        }
        coroutineScope.launch(Dispatchers.IO) {
            child.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.warn("typst-runtime: $it") }
            }
        }
    }

    private fun acceptResponse(line: String) {
        val response = runCatching { gson.fromJson(line, JsonObject::class.java) }
            .onFailure { logger.warn("Invalid response from Typst runtime", it) }
            .getOrNull() ?: return
        val id = response.get("id")?.asString ?: return
        pending.remove(id)?.complete(response)
    }

    private suspend fun request(
        source: VirtualFile,
        documentVersion: Long,
        requestGeneration: Long,
        method: String,
        params: Map<String, Any?>,
    ): JsonObject {
        val id = requestSequence.incrementAndGet().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val message = mapOf(
            "id" to id,
            "workspaceId" to workspaceId(),
            "documentVersion" to documentVersion,
            "generation" to requestGeneration,
            "method" to method,
            "params" to params,
        )
        try {
            withContext(Dispatchers.IO) {
                val output = writer ?: throw IllegalStateException("Typst runtime is not running")
                synchronized(output) {
                    output.write(gson.toJson(message))
                    output.newLine()
                    output.flush()
                }
            }
            val response = withTimeout(REQUEST_TIMEOUT_MILLIS) { deferred.await() }
            if (response.get("protocolVersion")?.asInt != PROTOCOL_VERSION) {
                status = TypstRuntimeStatus.INCOMPATIBLE
                throw IllegalStateException("Unsupported Typst runtime protocol")
            }
            if (response.get("status")?.asString != "ok") {
                val error = response.getAsJsonObject("error")?.get("message")?.asString ?: "Typst runtime request failed"
                throw IllegalStateException(error)
            }
            return response
        } catch (exception: TimeoutCancellationException) {
            throw IllegalStateException("Typst runtime request timed out", exception)
        } finally {
            pending.remove(id)
        }
    }

    private fun workspaceId(): String = project.locationHash

    private fun stopRuntimeForCancellation() {
        val child = process ?: return
        intentionalProcessStops.add(child.pid())
        child.descendants().forEach { it.destroyForcibly() }
        child.destroyForcibly()
    }

    private fun setStatus(value: TypstRuntimeStatus) {
        status = value
    }

    override fun dispose() {
        readerJob?.cancel()
        runCatching {
            writer?.apply {
                write(gson.toJson(mapOf(
                    "id" to "shutdown",
                    "workspaceId" to workspaceId(),
                    "documentVersion" to 0,
                    "generation" to generation.get(),
                    "method" to "shutdown",
                    "params" to emptyMap<String, Any>(),
                )))
                newLine()
                flush()
            }
        }
        process?.let { child ->
            intentionalProcessStops.add(child.pid())
            child.descendants().forEach { it.destroyForcibly() }
            child.destroy()
        }
        if (process?.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS) == false) process?.destroyForcibly()
        pending.values.forEach { it.cancel() }
        pending.clear()
        diagnosticsByUri.clear()
        overlayUris.clear()
        process = null
        writer = null
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val MAX_RESTARTS = 3
        private const val MAX_PACKAGE_BYTES = 100L * 1024L * 1024L
        private const val MAX_PACKAGE_INDEX_BYTES = 10L * 1024L * 1024L
        private const val PACKAGE_INDEX_TTL_MILLIS = 24L * 60L * 60L * 1000L
        private const val PACKAGE_INDEX_URL = "https://packages.typst.org/preview/index.json"
        private val CLI_DIAGNOSTIC_PATTERN = Regex("^(.*):(\\d+):(\\d+):\\s*(.*)$")

        fun getInstance(project: Project): TypstRuntimeService = project.service()
    }
}
