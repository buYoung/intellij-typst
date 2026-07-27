package com.livteam.typninja.preview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.livteam.typninja.language.TypstFileType

class TypstSplitEditorProvider :
    TextEditorWithPreviewProvider(TypstPreviewFileEditorProvider()),
    DumbAware {
    override fun createSplitEditor(
        firstEditor: TextEditor,
        secondEditor: FileEditor,
    ): FileEditor = TextEditorWithPreview(
        firstEditor,
        secondEditor,
        "Typst",
        TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW,
    )
}

private class TypstPreviewFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.isValid && !file.isDirectory && file.fileType == TypstFileType

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        TypstPreviewFileEditor(
            project,
            file,
            project.service<TypstPreviewBindingService>().bindingFor(file),
        )

    override fun getEditorTypeId(): String = "typst-preview"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
