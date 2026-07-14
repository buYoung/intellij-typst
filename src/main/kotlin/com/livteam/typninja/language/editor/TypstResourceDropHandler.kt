package com.livteam.typninja.language.editor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.FileDropEvent
import com.intellij.openapi.editor.FileDropHandler
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.livteam.typninja.language.TypstFileType
import com.livteam.typninja.settings.TypstSettingsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Copies files dropped into a Typst editor and inserts stable project-relative paths. */
class TypstResourceDropHandler : FileDropHandler {
    override suspend fun handleDrop(event: FileDropEvent): Boolean {
        val editor = event.editor ?: return false
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        if (virtualFile.fileType != TypstFileType || event.files.isEmpty()) return false
        val settingsService = TypstSettingsService.getInstance(event.project)
        if (!settingsService.state.enableResourcePaste) return false
        val sourcePath = Path.of(virtualFile.path).toAbsolutePath().normalize()
        val workspaceRoot = settingsService.workspaceRoot(sourcePath)
        val destinationFolder = resolveDestinationFolder(
            settingsService.state.resourcePasteFolder.orEmpty(),
            workspaceRoot,
            sourcePath,
        ) ?: return false
        try {
            val copiedFiles = withContext(Dispatchers.IO) {
                Files.createDirectories(destinationFolder)
                event.files.map { sourceFile ->
                    val source = sourceFile.toPath().toAbsolutePath().normalize()
                    val destination = availableDestination(destinationFolder, source.fileName.toString())
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    destination
                }
            }
            val insertion = copiedFiles.joinToString("\n") { copiedPath ->
                val relative = sourcePath.parent.relativize(copiedPath).toString().replace('\\', '/')
                if (copiedPath.extension().lowercase() in IMAGE_EXTENSIONS) "#image(\"$relative\")" else "\"$relative\""
            }
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(event.project, "Insert Typst resources", null, {
                    val startOffset = editor.selectionModel.selectionStart
                    val endOffset = editor.selectionModel.selectionEnd
                    editor.document.replaceString(startOffset, endOffset, insertion)
                    editor.caretModel.moveToOffset(startOffset + insertion.length)
                    editor.selectionModel.removeSelection()
                })
            }
            return true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            withContext(Dispatchers.EDT) {
                NotificationGroupManager.getInstance().getNotificationGroup("Typst")
                    .createNotification(
                        "Could not copy the dropped file: ${exception.message.orEmpty()}",
                        NotificationType.WARNING,
                    )
                    .notify(event.project)
            }
            return true
        }
    }

    private fun resolveDestinationFolder(pattern: String, root: Path, sourcePath: Path): Path? {
        val sourceDirectory = sourcePath.parent
        val relativeDirectory = runCatching { root.relativize(sourceDirectory).toString() }.getOrDefault("")
        val sourceName = sourcePath.fileName.toString().substringBeforeLast('.', sourcePath.fileName.toString())
        val configured = pattern.ifBlank { "\$root/assets" }
            .replace("\$root", root.toString())
            .replace("\$dir", relativeDirectory)
            .replace("\$name", sourceName)
        val path = runCatching { Path.of(configured) }.getOrNull() ?: return null
        val resolved = (if (path.isAbsolute) path else root.resolve(path)).normalize()
        return resolved.takeIf { it.startsWith(root) }
    }

    private fun availableDestination(folder: Path, fileName: String): Path {
        val initial = folder.resolve(fileName)
        if (!Files.exists(initial)) return initial
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        val baseName = if (extension.isEmpty()) fileName else fileName.removeSuffix(".$extension")
        var number = 2
        while (true) {
            val candidateName = if (extension.isEmpty()) "$baseName-$number" else "$baseName-$number.$extension"
            val candidate = folder.resolve(candidateName)
            if (!Files.exists(candidate)) return candidate
            number++
        }
    }

    private fun Path.extension(): String = fileName.toString().substringAfterLast('.', missingDelimiterValue = "")

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "svg", "webp")
    }
}
