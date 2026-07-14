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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.Comparator
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
    private val previewDirectories = ConcurrentHashMap.newKeySet<Path>()
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val latestResults = ConcurrentHashMap<String, TypstPreviewResult>()

    fun addListener(listener: (TypstPreviewResult) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun statusFor(source: VirtualFile?): TypstPreviewResult? = source?.path?.let(latestResults::get)

    fun preview(source: VirtualFile, unsavedText: String? = null) =
        compile(source, "png", null, unsavedText, PREVIEW_CHANNEL)

    fun export(source: VirtualFile, format: String, destination: Path, unsavedText: String? = null) =
        compile(source, format, destination, unsavedText, "export:$format")

    private fun compile(
        changedSource: VirtualFile,
        format: String,
        destination: Path?,
        unsavedText: String?,
        channel: String,
    ) {
        val normalizedFormat = format.lowercase()
        if (normalizedFormat !in CLI_FORMATS) {
            publish(TypstPreviewResult(emptyList(), normalizedFormat, changedSource, "Unsupported Typst export format"))
            return
        }
        val capability = TypstToolchainService.getInstance(project).currentCapability()
        if (normalizedFormat in CLI_FORMATS && (!capability.isValid || capability.executablePath == null)) {
            publish(TypstPreviewResult(emptyList(), normalizedFormat, changedSource,
                capability.failureMessage ?: "Typst executable is unavailable"))
            return
        }

        val generationCounter = channelGenerations.computeIfAbsent(channel) { AtomicLong() }
        val generation = generationCounter.incrementAndGet()
        activeJobs.remove(channel)?.cancel()
        activeProcesses.remove(channel)?.destroyForcibly()
        publish(TypstPreviewResult(emptyList(), normalizedFormat, changedSource, isRunning = true))

        activeJobs[channel] = coroutineScope.launch {
            val startedAt = System.nanoTime()
            val settings = TypstSettingsService.getInstance(project)
            val changedSourcePath = Path.of(changedSource.path).toAbsolutePath().normalize()
            val originalRoot = settings.workspaceRoot(changedSourcePath)
            val originalEntrypoint = settings.mainFile(changedSourcePath)
            val output = destination ?: createPreviewOutput(generation, normalizedFormat)
            var overlay: CompilationOverlay? = null
            try {
                val outputParent = output.parent ?: throw IllegalArgumentException("Typst output needs a parent directory")
                withContext(Dispatchers.IO) { Files.createDirectories(outputParent) }
                overlay = unsavedText?.let {
                    withContext(Dispatchers.IO) { createOverlay(originalRoot, changedSourcePath, originalEntrypoint, it) }
                }
                val compileRoot = overlay?.root ?: originalRoot
                val compileEntrypoint = overlay?.entrypoint ?: originalEntrypoint
                val command = buildCommand(
                    executablePath = capability.executablePath!!,
                    root = compileRoot,
                    entrypoint = compileEntrypoint,
                    output = pageOutputPath(output, normalizedFormat),
                    format = normalizedFormat,
                    isPreview = destination == null,
                    changedSourcePath = changedSourcePath,
                )
                val commandResult = withContext(Dispatchers.IO) { runCommand(channel, command) }
                if (commandResult.exitCode != 0) {
                    publishCurrent(channel, generation, TypstPreviewResult(
                        emptyList(), normalizedFormat, changedSource,
                        commandResult.error.ifBlank { "Typst exited with code ${commandResult.exitCode}" },
                        durationMillis = elapsedMillis(startedAt),
                    ))
                    return@launch
                }
                val outputFiles = withContext(Dispatchers.IO) { refreshOutputs(pageOutputPath(output, normalizedFormat)) }
                publishCurrent(channel, generation, TypstPreviewResult(
                    outputFiles, normalizedFormat, changedSource, durationMillis = elapsedMillis(startedAt),
                ))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                publishCurrent(channel, generation, TypstPreviewResult(
                    emptyList(), normalizedFormat, changedSource,
                    exception.message ?: "Typst compilation failed",
                    durationMillis = elapsedMillis(startedAt),
                ))
            } finally {
                overlay?.let { withContext(Dispatchers.IO) { deleteDirectory(it.root) } }
                if (channelGenerations[channel]?.get() == generation) activeJobs.remove(channel)
            }
        }
    }

    private fun buildCommand(
        executablePath: String,
        root: Path,
        entrypoint: Path,
        output: Path,
        format: String,
        isPreview: Boolean,
        changedSourcePath: Path,
    ): GeneralCommandLine {
        val settings = TypstSettingsService.getInstance(project)
        val command = GeneralCommandLine(executablePath)
            .withWorkDirectory(root.toFile())
            .withParameters("compile", "--format=$format")
        val extraArguments = settings.extraArguments() + if (isPreview) settings.previewArguments() else emptyList()
        command.addParameters(sanitizeArguments(extraArguments))
        command.addParameters("--root", root.toString())
        settings.resolvedFontPaths(changedSourcePath).forEach { command.addParameters("--font-path", it.toString()) }
        if (!settings.state.useSystemFonts) command.addParameter("--ignore-system-fonts")
        settings.state.packagePath.orEmpty().takeIf(String::isNotBlank)?.let { command.addParameters("--package-path", it) }
        settings.state.packageCachePath.orEmpty().takeIf(String::isNotBlank)?.let { command.addParameters("--package-cache-path", it) }
        if (format == "html" && command.parametersList.parameters.none { it.startsWith("--features") }) {
            command.addParameter("--features=html")
        }
        if (format == "png") command.addParameter("--ppi=${settings.state.previewPpi}")
        command.addParameters(entrypoint.toString(), output.toString())
        return command
    }

    private fun sanitizeArguments(arguments: List<String>): List<String> {
        val result = ArrayList<String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            val option = argument.substringBefore('=')
            if (option in DEDICATED_OPTIONS) {
                if ('=' !in argument && option != "--ignore-system-fonts") index++
            } else {
                result.add(argument)
            }
            index++
        }
        return result
    }

    private suspend fun runCommand(channel: String, commandLine: GeneralCommandLine): CommandResult = coroutineScope {
        val process = commandLine.createProcess()
        activeProcesses[channel] = process
        try {
            val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            val error = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                ensureActive()
                delay(1)
            }
            CommandResult(process.exitValue(), output.await(), error.await().trim())
        } finally {
            activeProcesses.remove(channel, process)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private suspend fun createOverlay(root: Path, changedFile: Path, entrypoint: Path, unsavedText: String): CompilationOverlay {
        if (!changedFile.startsWith(root) || !entrypoint.startsWith(root)) {
            throw IllegalArgumentException("The edited file and main file must be inside the configured Typst root")
        }
        val overlayRoot = Files.createTempDirectory("typst-source-overlay-")
        previewDirectories.add(overlayRoot)
        mirrorTree(root, overlayRoot, root.relativize(changedFile), unsavedText, 0)
        return CompilationOverlay(overlayRoot, overlayRoot.resolve(root.relativize(entrypoint)))
    }

    private suspend fun mirrorTree(
        sourceDirectory: Path,
        targetDirectory: Path,
        changedRelativePath: Path,
        unsavedText: String,
        depth: Int,
    ) {
        Files.createDirectories(targetDirectory)
        Files.newDirectoryStream(sourceDirectory).use { children ->
            for (child in children) {
                currentCoroutineContext().ensureActive()
                val target = targetDirectory.resolve(child.fileName.toString())
                val changedSegment = changedRelativePath.getName(depth)
                if (child.fileName == changedSegment) {
                    if (depth == changedRelativePath.nameCount - 1) {
                        Files.writeString(target, unsavedText, StandardCharsets.UTF_8)
                    } else {
                        mirrorTree(child, target, changedRelativePath, unsavedText, depth + 1)
                    }
                } else {
                    linkOrMirror(child, target)
                }
            }
        }
    }

    private suspend fun linkOrMirror(source: Path, target: Path) {
        currentCoroutineContext().ensureActive()
        runCatching { Files.createSymbolicLink(target, source) }.getOrElse {
            if (Files.isDirectory(source)) {
                Files.createDirectories(target)
                Files.newDirectoryStream(source).use { children ->
                    children.forEach { child -> linkOrMirror(child, target.resolve(child.fileName.toString())) }
                }
            } else {
                runCatching { Files.createLink(target, source) }
                    .getOrElse { Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES) }
            }
        }
    }

    private fun createPreviewOutput(generation: Long, format: String): Path {
        val directory = Files.createTempDirectory("typst-preview-$generation-")
        previewDirectories.add(directory)
        return directory.resolve(if (format in PAGED_IMAGE_FORMATS) "preview-{p}.$format" else "preview.$format")
    }

    private fun pageOutputPath(output: Path, format: String): Path {
        if (format !in PAGED_IMAGE_FORMATS || output.fileName.toString().contains("{p}")) return output
        val fileName = output.fileName.toString()
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = format)
        val baseName = fileName.removeSuffix(".$extension")
        return output.resolveSibling("$baseName-{p}.$extension")
    }

    private fun refreshOutputs(output: Path): List<VirtualFile> {
        val outputName = output.fileName.toString()
        val paths = if ("{p}" in outputName) {
            val glob = outputName.replace("{p}", "*")
            Files.newDirectoryStream(output.parent, glob).use { files -> files.toList().sortedBy(::pageNumber) }
        } else listOf(output)
        return paths.mapNotNull { path -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) }
    }

    private fun pageNumber(path: Path): Int = PAGE_NUMBER_PATTERN.find(path.fileName.toString())
        ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun publishCurrent(channel: String, generation: Long, result: TypstPreviewResult) {
        if (channelGenerations[channel]?.get() != generation) return
        ApplicationManager.getApplication().invokeLater {
            if (channelGenerations[channel]?.get() == generation) publish(result)
        }
    }

    private fun publish(result: TypstPreviewResult) {
        result.sourceFile?.path?.let { latestResults[it] = result }
        listeners.forEach { listener -> listener(result) }
        if (!result.isRunning) result.failureMessage?.let { message ->
            NotificationGroupManager.getInstance().getNotificationGroup("Typst")
                .createNotification(message, NotificationType.WARNING)
                .notify(project)
        }
    }

    override fun dispose() {
        activeJobs.values.forEach(Job::cancel)
        activeProcesses.values.forEach(Process::destroyForcibly)
        previewDirectories.forEach(::deleteDirectory)
        activeJobs.clear()
        activeProcesses.clear()
        previewDirectories.clear()
        latestResults.clear()
        listeners.clear()
    }

    private fun deleteDirectory(directory: Path) {
        previewDirectories.remove(directory)
        if (!Files.exists(directory)) return
        runCatching {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private data class CompilationOverlay(val root: Path, val entrypoint: Path)
    private data class CommandResult(val exitCode: Int, val output: String, val error: String)

    companion object {
        private const val PREVIEW_CHANNEL = "preview"
        private val CLI_FORMATS = setOf("pdf", "png", "svg", "html")
        private val PAGED_IMAGE_FORMATS = setOf("png", "svg")
        private val DEDICATED_OPTIONS = setOf(
            "--root", "--font-path", "--ignore-system-fonts", "--package-path", "--package-cache-path",
        )
        private val PAGE_NUMBER_PATTERN = Regex("-(\\d+)[.][^.]+$")

        fun getInstance(project: Project): TypstPreviewService = project.service()
    }
}
