package com.livteam.typninja.preview

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.livteam.typninja.execution.TypstToolchainService
import com.livteam.typninja.settings.TypstSettingsService
import com.livteam.typninja.runtime.TypstRuntimeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class TypstPreviewResult(
    val outputFiles: List<VirtualFile>,
    val format: String,
    val sourceFile: VirtualFile? = null,
    val failureMessage: String? = null,
    val isRunning: Boolean = false,
    val durationMillis: Long? = null,
    val previewUrl: String? = null,
    val sourceMappingAvailable: Boolean = false,
    val runtimeGeneration: Long? = null,
    val documentVersion: Long? = null,
    val pageCount: Int = outputFiles.size,
) {
    val outputFile: VirtualFile? get() = outputFiles.firstOrNull()
}

/** Compiles Typst documents for preview/export without starting Tinymist or another language server. */
@Service(Service.Level.PROJECT)
class TypstPreviewService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private val listeners = ConcurrentHashMap.newKeySet<(TypstPreviewResult) -> Unit>()
    private val channelGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeRequests = ConcurrentHashMap<String, CompilationRequest>()
    private val latestResults = ConcurrentHashMap<String, TypstPreviewResult>()
    private val latestSuccessfulResults = ConcurrentHashMap<String, TypstPreviewResult>()
    @Volatile
    private var currentPreviewSourcePath: String? = null

    fun addListener(listener: (TypstPreviewResult) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun statusFor(source: VirtualFile?): TypstPreviewResult? = source?.path?.let(latestResults::get)

    fun lastSuccessfulFor(source: VirtualFile?): TypstPreviewResult? =
        source?.path?.let(latestSuccessfulResults::get)

    fun isCurrentPreviewFor(source: VirtualFile): Boolean = currentPreviewSourcePath == source.path

    fun preview(source: VirtualFile, unsavedText: String? = null, documentVersion: Long = source.modificationStamp) =
        compile(source, source, "svg", null, unsavedText, PREVIEW_CHANNEL, documentVersion)

    fun previewChangedSource(
        previewSource: VirtualFile,
        changedSource: VirtualFile,
        unsavedText: String?,
        documentVersion: Long,
    ) = compile(previewSource, changedSource, "svg", null, unsavedText, PREVIEW_CHANNEL, documentVersion)

    fun export(source: VirtualFile, format: String, destination: Path, unsavedText: String? = null) =
        compile(source, source, format, destination, unsavedText, "export:$format", source.modificationStamp)

    private fun compile(
        previewSource: VirtualFile,
        changedSource: VirtualFile,
        format: String,
        destination: Path?,
        unsavedText: String?,
        channel: String,
        documentVersion: Long,
    ) {
        val normalizedFormat = format.lowercase()
        if (normalizedFormat !in OUTPUT_FORMATS) {
            publish(TypstPreviewResult(emptyList(), normalizedFormat, previewSource, "Unsupported Typst export format"))
            return
        }
        val request = CompilationRequest(
            previewSourcePath = previewSource.path,
            changedSourcePath = changedSource.path,
            format = normalizedFormat,
            destination = destination,
            unsavedText = unsavedText,
            documentVersion = documentVersion,
        )
        if (activeJobs[channel]?.isActive == true && activeRequests[channel] == request) return
        if (channel == PREVIEW_CHANNEL) currentPreviewSourcePath = previewSource.path
        val generationCounter = channelGenerations.computeIfAbsent(channel) { AtomicLong() }
        val generation = generationCounter.incrementAndGet()
        activeJobs.remove(channel)?.cancel()
        publish(TypstPreviewResult(emptyList(), normalizedFormat, previewSource, isRunning = true))

        activeRequests[channel] = request
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            val startedAt = System.nanoTime()
            try {
                val capability = TypstToolchainService.getInstance(project).awaitCapability()
                check(capability.isValid) { capability.failureMessage ?: "Typst WASM engine is unavailable" }
                val runtime = TypstRuntimeService.getInstance(project)
                if (destination == null) {
                    val result = runtime.compileChangedSource(previewSource, changedSource, unsavedText, render = true, documentVersion)
                    publishCurrent(channel, generation, TypstPreviewResult(
                        outputFiles = emptyList(), format = "svg", sourceFile = previewSource,
                        failureMessage = if (result.outputStatus == "failed") result.diagnostics.firstOrNull { it.severity == "error" }?.message ?: "Typst compilation failed" else null,
                        durationMillis = elapsedMillis(startedAt), previewUrl = result.previewUrl,
                        sourceMappingAvailable = result.sourceMappingAvailable, runtimeGeneration = result.generation,
                        documentVersion = result.documentVersion, pageCount = result.pages.size,
                    ))
                } else {
                    val compiled = runtime.compileForExport(previewSource, unsavedText, normalizedFormat)
                    check(compiled.output["isSuccess"].asBoolean) {
                        compiled.output.getAsJsonArray("diagnostics").filter { it.asJsonObject["severity"].asString == "error" }
                            .joinToString("\n") { it.asJsonObject["message"].asString }.ifBlank { "Typst compilation failed" }
                    }
                    val outputFiles = withContext(Dispatchers.IO) {
                        val output = pageOutputPath(destination.toAbsolutePath().normalize(), normalizedFormat)
                        val pages = compiled.output.getAsJsonArray("pages")
                        val contents = when (normalizedFormat) {
                            "pdf", "png" -> compiled.binary
                            "svg" -> compiled.output.getAsJsonArray("svgPages").map { it.asString.toByteArray(Charsets.UTF_8) }
                            else -> listOf(compiled.output["html"].asString.toByteArray(Charsets.UTF_8))
                        }
                        contents.mapIndexed { index, bytes ->
                            currentCoroutineContext().ensureActive()
                            check(channelGenerations[channel]?.get() == generation) { "Superseded Typst export" }
                            val path = if (normalizedFormat in PAGED_IMAGE_FORMATS)
                                output.resolveSibling(output.fileName.toString().replace("{p}", pages[index].asJsonObject["number"].asString)) else output
                            Files.createDirectories(path.parent)
                            val temporary = Files.createTempFile(path.parent, ".typst-export-", ".tmp")
                            try {
                                Files.write(temporary, bytes)
                                currentCoroutineContext().ensureActive()
                                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                            } finally { Files.deleteIfExists(temporary) }
                            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                        }.filterNotNull()
                    }
                    publishCurrent(channel, generation, TypstPreviewResult(outputFiles, normalizedFormat, previewSource, durationMillis = elapsedMillis(startedAt)))
                }
            } catch (exception: CancellationException) { throw exception
            } catch (exception: Exception) {
                publishCurrent(channel, generation, TypstPreviewResult(emptyList(), normalizedFormat, previewSource,
                    exception.message ?: "Typst WASM compilation failed", durationMillis = elapsedMillis(startedAt)))
            } finally {
                if (channelGenerations[channel]?.get() == generation) activeJobs.remove(channel)
                if (channelGenerations[channel]?.get() == generation) activeRequests.remove(channel, request)
            }
        }
        activeJobs[channel] = job
        job.start()
    }

    private fun pageOutputPath(output: Path, format: String): Path {
        if (format !in PAGED_IMAGE_FORMATS || output.fileName.toString().contains("{p}")) return output
        val fileName = output.fileName.toString()
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = format)
        val baseName = fileName.removeSuffix(".$extension")
        return output.resolveSibling("$baseName-{p}.$extension")
    }

    private fun publishCurrent(channel: String, generation: Long, result: TypstPreviewResult) {
        if (channelGenerations[channel]?.get() != generation) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && channelGenerations[channel]?.get() == generation) publish(result)
        }
    }

    private fun publish(result: TypstPreviewResult) {
        result.sourceFile?.path?.let { latestResults[it] = result }
        if (!result.isRunning && result.failureMessage == null &&
            (result.previewUrl != null || result.outputFiles.isNotEmpty())
        ) {
            result.sourceFile?.path?.let { latestSuccessfulResults[it] = result }
        }
        listeners.forEach { listener -> listener(result) }
        if (!result.isRunning) result.failureMessage?.let { message ->
            NotificationGroupManager.getInstance().getNotificationGroup("Typst")
                .createNotification(message, NotificationType.WARNING)
                .notify(project)
        }
    }

    override fun dispose() {
        activeJobs.values.forEach(Job::cancel)
        activeJobs.clear()
        activeRequests.clear()
        latestResults.clear()
        latestSuccessfulResults.clear()
        currentPreviewSourcePath = null
        listeners.clear()
    }

    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private data class CompilationRequest(
        val previewSourcePath: String,
        val changedSourcePath: String,
        val format: String,
        val destination: Path?,
        val unsavedText: String?,
        val documentVersion: Long,
    )

    companion object {
        private const val PREVIEW_CHANNEL = "preview"
        private val OUTPUT_FORMATS = setOf("pdf", "png", "svg", "html")
        private val PAGED_IMAGE_FORMATS = setOf("png", "svg")

        fun getInstance(project: Project): TypstPreviewService = project.service()
    }
}
