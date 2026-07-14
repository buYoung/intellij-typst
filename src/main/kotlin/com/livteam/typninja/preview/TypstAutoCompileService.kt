package com.livteam.typninja.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.livteam.typninja.language.TypstFileType
import com.livteam.typninja.settings.TypstSettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Runs configured preview/export tasks on save or after a short typing pause. */
@Service(Service.Level.PROJECT)
class TypstAutoCompileService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private var typingJob: Job? = null

    init {
        project.messageBus.connect(this).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    schedule(document, "onSave", immediately = true)
                }
            },
        )
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    schedule(event.document, "onType", immediately = false)
                }
            },
            this,
        )
    }

    private fun schedule(document: Document, trigger: String, immediately: Boolean) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (file.fileType != TypstFileType || !belongsToProject(file)) return
        val settings = TypstSettingsService.getInstance(project).state
        if (settings.autoPreview != trigger && settings.autoExport != trigger) return
        typingJob?.cancel()
        typingJob = coroutineScope.launch {
            if (!immediately) delay(450)
            runConfiguredTasks(file, document.text, trigger)
        }
    }

    private fun runConfiguredTasks(file: VirtualFile, currentText: String, trigger: String) {
        val settingsService = TypstSettingsService.getInstance(project)
        val settings = settingsService.state
        val previewService = TypstPreviewService.getInstance(project)
        if (settings.autoPreview == trigger) previewService.preview(file, currentText)
        if (settings.autoExport == trigger) {
            val sourcePath = Path.of(file.path).toAbsolutePath().normalize()
            val entrypoint = settingsService.mainFile(sourcePath)
            val format = TypstExportSettings.format(settings)
            val destination = TypstOutputPathResolver.resolve(settingsService, entrypoint, format)
            previewService.export(file, format, destination, currentText)
        }
    }

    private fun belongsToProject(file: VirtualFile): Boolean {
        val root = project.basePath?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() } ?: return true
        val filePath = runCatching { Path.of(file.path).toAbsolutePath().normalize() }.getOrNull() ?: return false
        return filePath.startsWith(root)
    }

    override fun dispose() {
        typingJob?.cancel()
    }
}

class TypstAutoCompileStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<TypstAutoCompileService>()
    }
}

object TypstOutputPathResolver {
    fun resolve(settings: TypstSettingsService, entrypoint: Path, format: String): Path {
        val root = settings.workspaceRoot(entrypoint)
        val normalizedEntrypoint = entrypoint.toAbsolutePath().normalize()
        val relativeParent = runCatching { root.relativize(normalizedEntrypoint.parent).toString() }.getOrDefault("")
        val sourceName = normalizedEntrypoint.fileName.toString().removeSuffix(".typ")
        val configuredPattern = settings.state.outputPathPattern.orEmpty()
        val pattern = configuredPattern.ifBlank { "\$dir/\$name" }
        val substituted = pattern
            .replace("\$root", root.toString())
            .replace("\$dir", relativeParent)
            .replace("\$name", sourceName)
            .replace("\$format", format)
        val explicitAbsolute = pattern.contains("\$root") || runCatching { Path.of(pattern).isAbsolute }.getOrDefault(false)
        var output = Path.of(if (explicitAbsolute) substituted else substituted.trimStart('/', '\\'))
        if (!output.isAbsolute) output = root.resolve(output)
        if (settings.state.exportTarget == "bundle") {
            output = output.resolve("index.html")
        }
        if (output.fileName.toString().substringAfterLast('.', missingDelimiterValue = "") != format) {
            output = output.resolveSibling("${output.fileName}.$format")
        }
        return output.normalize()
    }
}

object TypstExportSettings {
    fun format(settings: TypstSettingsService.Settings): String = when (settings.exportTarget) {
        "html", "bundle" -> "html"
        else -> settings.defaultExportFormat.orEmpty().ifBlank { "pdf" }.lowercase()
    }
}
