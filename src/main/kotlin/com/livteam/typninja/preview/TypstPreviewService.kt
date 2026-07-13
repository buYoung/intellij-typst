package com.livteam.typninja.preview

import com.intellij.execution.configurations.GeneralCommandLine
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class TypstPreviewResult(
    val outputFiles: List<VirtualFile>,
    val format: String,
    val failureMessage: String? = null,
) {
    val outputFile: VirtualFile? get() = outputFiles.firstOrNull()
}

/** Runs only a validated Typst CLI against saved source files and publishes isolated output. */
@Service(Service.Level.PROJECT)
class TypstPreviewService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private val listeners = ConcurrentHashMap.newKeySet<(TypstPreviewResult) -> Unit>()
    private val requestGeneration = AtomicLong()
    private val previewDirectories = ConcurrentHashMap.newKeySet<Path>()
    private val activeProcess = AtomicReference<Process?>()

    @Volatile
    private var activeJob: Job? = null

    fun addListener(listener: (TypstPreviewResult) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun preview(source: VirtualFile) = compile(source, "png", null)

    fun export(source: VirtualFile, format: String, destination: Path) = compile(source, format, destination)

    private fun compile(source: VirtualFile, format: String, destination: Path?) {
        val capability = TypstToolchainService.getInstance(project).currentCapability()
        if (!capability.isValid || capability.executablePath == null) {
            publish(TypstPreviewResult(emptyList(), format, capability.failureMessage ?: "Typst executable is unavailable"))
            return
        }
        val generation = requestGeneration.incrementAndGet()
        activeJob?.cancel()
        activeProcess.getAndSet(null)?.destroyForcibly()
        val sourceModificationStamp = source.modificationStamp
        activeJob = coroutineScope.launch {
            val output = destination ?: createPreviewOutput(generation, format)
            try {
                val outputParent = output.parent ?: throw IllegalArgumentException("Typst output needs a parent directory")
                withContext(Dispatchers.IO) { Files.createDirectories(outputParent) }
                val command = GeneralCommandLine(capability.executablePath)
                    .withWorkDirectory(source.parent.path)
                    .withParameters("compile", "--format=$format", "--root", source.parent.path)
                if (format == "png") command.addParameter("--ppi=${TypstSettingsService.getInstance(project).state.previewPpi}")
                command.addParameters(source.path, output.toString())
                val result = withContext(Dispatchers.IO) { runCommand(command) }
                if (result.exitCode != 0) {
                    publishCurrent(generation, source, sourceModificationStamp, TypstPreviewResult(emptyList(), format,
                        result.error.ifBlank { "Typst exited with code ${result.exitCode}" }))
                    return@launch
                }
                val outputFiles = withContext(Dispatchers.IO) { refreshOutputs(output, format) }
                publishCurrent(generation, source, sourceModificationStamp, TypstPreviewResult(outputFiles, format))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                publishCurrent(generation, source, sourceModificationStamp, TypstPreviewResult(emptyList(), format,
                    exception.message ?: "Typst compilation failed"))
            }
        }
    }

    private suspend fun runCommand(commandLine: GeneralCommandLine): CommandResult = coroutineScope {
        val process = commandLine.createProcess()
        activeProcess.set(process)
        try {
            val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            val error = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                ensureActive()
                delay(1)
            }
            CommandResult(process.exitValue(), output.await(), error.await().trim())
        } finally {
            activeProcess.compareAndSet(process, null)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun createPreviewOutput(generation: Long, format: String): Path {
        val directory = Files.createTempDirectory("typst-preview-$generation-")
        previewDirectories.add(directory)
        return directory.resolve(if (format == "png") "preview-{p}.png" else "preview.$format")
    }

    private fun refreshOutputs(output: Path, format: String): List<VirtualFile> {
        val paths = if (format == "png") {
            val directory = output.parent ?: return emptyList()
            Files.newDirectoryStream(directory, "preview-*.png").use { files -> files.toList().sorted() }
        } else listOf(output)
        return paths.mapNotNull { path -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) }
    }

    private fun publishCurrent(
        generation: Long,
        source: VirtualFile,
        sourceModificationStamp: Long,
        result: TypstPreviewResult,
    ) {
        if (requestGeneration.get() != generation || !source.isValid || source.modificationStamp != sourceModificationStamp) return
        ApplicationManager.getApplication().invokeLater { publish(result) }
    }

    private fun publish(result: TypstPreviewResult) {
        listeners.forEach { listener -> listener(result) }
        result.failureMessage?.let { message ->
            NotificationGroupManager.getInstance().getNotificationGroup("Typst")
                .createNotification(message, NotificationType.WARNING)
                .notify(project)
        }
    }

    override fun dispose() {
        activeJob?.cancel()
        activeProcess.getAndSet(null)?.destroyForcibly()
        previewDirectories.forEach(::deleteDirectory)
        previewDirectories.clear()
        listeners.clear()
    }

    private fun deleteDirectory(directory: Path) {
        if (!Files.exists(directory)) return
        runCatching {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String, val error: String)

    companion object {
        fun getInstance(project: Project): TypstPreviewService = project.service()
    }
}
