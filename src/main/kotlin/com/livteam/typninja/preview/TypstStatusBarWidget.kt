package com.livteam.typninja.preview

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedStatusBarPopup
import com.livteam.typninja.language.TypstFileType

class TypstStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "Typst document status"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = TypstStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
    override fun isEnabledByDefault(): Boolean = true

    companion object {
        const val WIDGET_ID = "com.livteam.typninja.status"
    }
}

private class TypstStatusBarWidget(private val typstProject: Project) : EditorBasedStatusBarPopup(typstProject, false) {
    private val removePreviewListener = TypstPreviewService.getInstance(typstProject).addListener { update() }

    override fun ID(): String = TypstStatusBarWidgetFactory.WIDGET_ID

    override fun createInstance(project: Project): StatusBarWidget = TypstStatusBarWidget(project)

    override fun isEnabledForFile(file: VirtualFile?): Boolean = file?.fileType == TypstFileType

    override fun getWidgetState(file: VirtualFile?): WidgetState {
        if (file?.fileType != TypstFileType) return WidgetState.HIDDEN
        val document = FileDocumentManager.getInstance().getDocument(file)
        val wordCount = document?.charsSequence?.let { WORD_PATTERN.findAll(it).count() } ?: 0
        val result = TypstPreviewService.getInstance(typstProject).statusFor(file)
        val compileState = when {
            result?.isRunning == true -> "rendering"
            result?.failureMessage != null -> "failed"
            result?.durationMillis != null -> "${result.durationMillis}ms"
            else -> "ready"
        }
        val text = "Typst · ${file.name} · $wordCount words · $compileState"
        return WidgetState("Typst preview, export, and document status", text, true)
    }

    override fun createPopup(context: DataContext): ListPopup {
        val actionManager = ActionManager.getInstance()
        val group = DefaultActionGroup().apply {
            actionManager.getAction("com.livteam.typninja.preview")?.let(::add)
            actionManager.getAction("com.livteam.typninja.export")?.let(::add)
            addSeparator()
            add(object : AnAction("Typst Settings") {
                override fun actionPerformed(event: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(typstProject, "Typst")
                }
            })
        }
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Typst",
            group,
            context,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false,
            ActionPlaces.STATUS_BAR_PLACE,
        )
    }

    override fun dispose() {
        removePreviewListener()
        super.dispose()
    }

    private companion object {
        val WORD_PATTERN = Regex("[\\p{L}\\p{N}_-]+")
    }
}
