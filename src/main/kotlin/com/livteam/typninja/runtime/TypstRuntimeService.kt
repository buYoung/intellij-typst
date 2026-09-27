package com.livteam.typninja.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.livteam.typninja.language.analysis.TypstProjectModelService
import com.livteam.typninja.language.references.TypstPackageResolver
import com.livteam.typninja.settings.TypstSettingsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class TypstRuntimeService(private val project: Project, private val coroutineScope: CoroutineScope) : Disposable {
    private val logger = Logger.getInstance(TypstRuntimeService::class.java)
    private val gson = Gson()
    private val compilerMutex = Mutex()
    private val generation = AtomicLong()
    private val previewGeneration = AtomicLong()
    private val diagnosticsByPath = ConcurrentHashMap<String, List<TypstRuntimeDiagnostic>>()
    private val diagnosticPathsBySource = ConcurrentHashMap<String, Set<String>>()
    private val packageStatuses = ConcurrentHashMap<String, TypstPackageStatus>()
    private val diagnosticJobs = ConcurrentHashMap<String, Job>()
    private var compiler: TypstWasmRuntime? = null
    private var compilerFingerprint: String? = null
    private var previewServer: TypstPreviewServer? = null
    private var previewServerSourcePath: String? = null
    @Volatile private var previewSnapshot: PreviewSnapshot? = null
    @Volatile var status: TypstRuntimeStatus = TypstRuntimeStatus.UNINSTALLED
        private set

    private data class PreviewSnapshot(val generation: Long, val documentVersion: Long, val sourcePath: String, val workspace: TypstWasmWorkspace)

    fun diagnosticsFor(file: VirtualFile): List<TypstRuntimeDiagnostic> =
        diagnosticsByPath[Path.of(file.path).toAbsolutePath().normalize().toString()].orEmpty()
    fun packageStatus(specification: String): TypstPackageStatus? = packageStatuses[specification]

    fun requestCompile(source: VirtualFile, unsavedText: String?, render: Boolean, documentVersion: Long = source.modificationStamp): Job {
        diagnosticJobs.remove(source.path)?.cancel()
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) { compile(source, unsavedText, render, documentVersion) }
        diagnosticJobs[source.path] = job
        job.invokeOnCompletion { diagnosticJobs.remove(source.path, job) }
        job.start()
        return job
    }

    suspend fun compile(source: VirtualFile, unsavedText: String?, render: Boolean, documentVersion: Long = source.modificationStamp): TypstRuntimeCompileResult =
        compileChangedSource(source, source, unsavedText, render, documentVersion)

    suspend fun compileChangedSource(previewSource: VirtualFile, changedSource: VirtualFile, unsavedText: String?, render: Boolean, documentVersion: Long): TypstRuntimeCompileResult {
        val currentGeneration = generation.incrementAndGet()
        val requestedPreviewGeneration = if (render) previewGeneration.incrementAndGet() else 0L
        return try {
            val compilation = compileWasm(previewSource, changedSource, unsavedText, if (render) "svg" else "check", render) { result ->
                if (render && previewGeneration.get() == requestedPreviewGeneration && result.output["isSuccess"].asBoolean) {
                    if (previewServerSourcePath != previewSource.path) {
                        previewServer?.dispose()
                        previewServer = null
                        previewServerSourcePath = previewSource.path
                    }
                    val server = previewServer ?: TypstPreviewServer().also { previewServer = it }
                    server.update(currentGeneration, result.output)
                    previewSnapshot = PreviewSnapshot(currentGeneration, documentVersion, previewSource.path, result.workspace)
                }
            }
            val diagnostics = diagnostics(compilation, previewSource)
            publishDiagnostics(previewSource.path, diagnostics)
            TypstRuntimeCompileResult(currentGeneration, documentVersion,
                if (compilation.output["isSuccess"].asBoolean) "success" else "failed", diagnostics,
                compilation.output.getAsJsonArray("pages").map { gson.fromJson(it, TypstRuntimePage::class.java) },
                render && previewSnapshot?.generation == currentGeneration && compilation.output["hasSourceMap"].asBoolean,
                if (render && previewSnapshot?.generation == currentGeneration && compilation.output["isSuccess"].asBoolean) previewServer?.url else null)
        } catch (exception: CancellationException) { throw exception
        } catch (exception: Exception) {
            status = TypstRuntimeStatus.FAILED
            logger.warn("Typst WASM compilation failed", exception)
            val diagnostic = TypstRuntimeDiagnostic("error", exception.message ?: "Typst WASM compilation failed", Path.of(previewSource.path).toUri().toString(), 0, 0, 0, 1)
            publishDiagnostics(previewSource.path, listOf(diagnostic))
            TypstRuntimeCompileResult(currentGeneration, documentVersion, "failed", listOf(diagnostic), emptyList(), false, null)
        }
    }

    internal suspend fun compileForExport(source: VirtualFile, unsavedText: String?, format: String): TypstWasmCompilation =
        compileWasm(source, source, unsavedText, format, false)

    private suspend fun compileWasm(source: VirtualFile, changedSource: VirtualFile, explicitText: String?, format: String, isPreview: Boolean,
                                    onCompiled: (TypstWasmCompilation) -> Unit = {}): TypstWasmCompilation {
        val settings = TypstSettingsService.getInstance(project)
        val root = settings.workspaceRoot(Path.of(source.path)).toAbsolutePath().normalize()
        val main = settings.mainFile(Path.of(source.path)).toAbsolutePath().normalize()
        val options = TypstWasmOptions.from(settings, format, isPreview)
        val version = settings.state.typstWasmVersion?.takeIf(String::isNotBlank) ?: TypstWasmInstaller.defaultVersion
        val fontPaths = settings.resolvedFontPaths(Path.of(source.path))
        val shouldUseSystemFonts = settings.state.useSystemFonts
        val overlays = readAction {
            buildMap {
                val manager = FileDocumentManager.getInstance()
                for (document in manager.unsavedDocuments) {
                    val file = manager.getFile(document) ?: continue
                    val path = Path.of(file.path).toAbsolutePath().normalize()
                    if (path.startsWith(root)) put(path, document.text)
                }
                if (explicitText != null) {
                    val changed = Path.of(changedSource.path).toAbsolutePath().normalize()
                    require(changed.startsWith(root)) { "The edited file must be inside the configured Typst root" }
                    put(changed, explicitText)
                }
            }
        }
        val module = TypstWasmInstaller.resolve(version, settings.state.autoDownloadWasm) { status = it }
        return compilerMutex.withLock {
            try {
                runInterruptible(Dispatchers.IO) {
                    val fonts = TypstWasmFonts.find(fontPaths, shouldUseSystemFonts)
                    val fingerprint = listOf(module, fonts.map { listOf(it, java.nio.file.Files.getLastModifiedTime(it).toMillis(), java.nio.file.Files.size(it)) }).toString()
                    val runtime = compiler?.takeIf { it.isUsable && compilerFingerprint == fingerprint } ?: run {
                        previewSnapshot = null
                        compiler = null
                        TypstWasmRuntime(module, version).also {
                            it.registerFonts(fonts)
                            compiler = it
                            compilerFingerprint = fingerprint
                        }
                    }
                    val workspace = TypstWasmWorkspace(project, root, overlays) { spec, state ->
                        packageStatuses[spec] = state
                        if (state == TypstPackageStatus.INSTALLED) {
                            TypstProjectModelService.getInstance(project).requestRefresh()
                            VirtualFileManager.getInstance().asyncRefresh(null)
                        }
                    }
                    workspace.compile(runtime, main, options).also {
                        TypstWasmRuntime.checkCancellation()
                        status = TypstRuntimeStatus.READY
                        onCompiled(it)
                    }
                }
            } catch (failure: Throwable) {
                // WASM traps and cancellation can interrupt an allocator or compiler mutation.
                compiler = null
                compilerFingerprint = null
                previewSnapshot = null
                throw failure
            }
        }
    }

    private fun diagnostics(compilation: TypstWasmCompilation, source: VirtualFile): List<TypstRuntimeDiagnostic> =
        compilation.output.getAsJsonArray("diagnostics").map { item ->
            val diagnostic = item.asJsonObject
            fun integer(key: String, fallback: Int) = diagnostic[key]?.takeUnless { it.isJsonNull }?.asInt ?: fallback
            val path = diagnostic["path"]?.takeUnless { it.isJsonNull }?.asString?.let(compilation.workspace::resolve) ?: Path.of(source.path)
            TypstRuntimeDiagnostic(diagnostic["severity"].asString, diagnostic["message"].asString, path.toUri().toString(),
                integer("startLine", 0), integer("startColumn", 0), integer("endLine", integer("startLine", 0)),
                integer("endColumn", integer("startColumn", 0) + 1), diagnostic.getAsJsonArray("hints").map { it.asString })
        }

    private suspend fun publishDiagnostics(sourcePath: String, diagnostics: List<TypstRuntimeDiagnostic>) {
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val grouped = diagnostics.groupBy { Path.of(URI(it.uri)).toAbsolutePath().normalize().toString() }
            diagnosticPathsBySource.put(sourcePath, grouped.keys)?.forEach(diagnosticsByPath::remove)
            grouped.forEach(diagnosticsByPath::put)
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    fun requestDocumentToSource(source: VirtualFile, documentVersion: Long, runtimeGeneration: Long, page: Int, x: Double, y: Double,
                                onMapped: (TypstRuntimeSourcePosition) -> Unit): Job = coroutineScope.launch {
        try {
            val position = compilerMutex.withLock {
                val snapshot = matchingSnapshot(source, documentVersion, runtimeGeneration) ?: return@withLock null
                runInterruptible(Dispatchers.IO) {
                    val result = compiler?.request(mapOf("method" to "documentToSource", "page" to page, "x" to x, "y" to y))?.takeUnless { it.isJsonNull }?.asJsonObject
                        ?: return@runInterruptible null
                    val path = snapshot.workspace.resolve(result["path"].asString) ?: return@runInterruptible null
                    TypstRuntimeSourcePosition(path.toUri().toString(), result["line"].asInt, result["column"].asInt, result["endLine"].asInt, result["endColumn"].asInt)
                }
            } ?: return@launch
            withContext(Dispatchers.EDT) { if (matchingSnapshot(source, documentVersion, runtimeGeneration) != null) onMapped(position) }
        } catch (exception: CancellationException) { throw exception
        } catch (exception: Exception) { logger.debug("Failed to map WASM preview to source", exception) }
    }

    fun requestSourceToDocument(source: VirtualFile, documentVersion: Long, runtimeGeneration: Long, position: TypstRuntimeSourcePosition,
                                onMapped: (List<TypstRuntimeDocumentPosition>) -> Unit): Job = coroutineScope.launch {
        try {
            val positions = compilerMutex.withLock {
                val snapshot = matchingSnapshot(source, documentVersion, runtimeGeneration) ?: return@withLock emptyList()
                val path = Path.of(URI(position.uri)).toAbsolutePath().normalize()
                val text = snapshot.workspace.sources[path] ?: return@withLock emptyList()
                val lines = text.split('\n')
                if (position.line !in lines.indices || position.column !in 0..lines[position.line].length) return@withLock emptyList()
                val offset = lines.take(position.line).sumOf { it.length + 1 } + position.column
                runInterruptible(Dispatchers.IO) {
                    compiler?.request(mapOf("method" to "sourceToDocument", "path" to snapshot.workspace.virtualPath(path), "utf16Offset" to offset))
                        ?.asJsonArray?.map { gson.fromJson(it, TypstRuntimeDocumentPosition::class.java) }.orEmpty()
                }
            }
            withContext(Dispatchers.EDT) { if (matchingSnapshot(source, documentVersion, runtimeGeneration) != null) onMapped(positions) }
        } catch (exception: CancellationException) { throw exception
        } catch (exception: Exception) { logger.debug("Failed to map source to WASM preview", exception) }
    }

    private fun matchingSnapshot(source: VirtualFile, documentVersion: Long, runtimeGeneration: Long): PreviewSnapshot? =
        previewSnapshot?.takeIf { it.sourcePath == source.path && it.documentVersion == documentVersion && it.generation == runtimeGeneration && compiler?.isUsable == true }

    fun ensurePreviewPackage(specification: String) {
        val spec = TypstPackageResolver.parse(specification) ?: return
        if (spec.namespace != "preview" || !TypstSettingsService.getInstance(project).state.autoDownloadPackages) return
        val previous = packageStatuses.putIfAbsent(specification, TypstPackageStatus.DOWNLOADING)
        if (previous != null && (previous != TypstPackageStatus.FAILED ||
                !packageStatuses.replace(specification, previous, TypstPackageStatus.DOWNLOADING))) return
        coroutineScope.launch {
            try {
                val installed = runInterruptible(Dispatchers.IO) { TypstPackageStorage.ensure(project, specification) }
                packageStatuses[specification] = if (installed != null) TypstPackageStatus.INSTALLED else TypstPackageStatus.FAILED
                TypstProjectModelService.getInstance(project).requestRefresh()
                VirtualFileManager.getInstance().asyncRefresh(null)
                withContext(Dispatchers.EDT) { if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart() }
            } catch (exception: CancellationException) {
                packageStatuses.remove(specification)
                throw exception
            } catch (exception: Exception) {
                packageStatuses[specification] = TypstPackageStatus.FAILED
                logger.warn("Failed to download Typst package $specification", exception)
            }
        }
    }

    override fun dispose() {
        diagnosticJobs.values.forEach(Job::cancel)
        previewServer?.dispose()
        previewServer = null
        compiler = null
        previewSnapshot = null
        diagnosticsByPath.clear()
        diagnosticPathsBySource.clear()
    }

    companion object { fun getInstance(project: Project): TypstRuntimeService = project.service() }
}
