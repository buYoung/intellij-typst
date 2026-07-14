package com.livteam.typninja.language.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import com.livteam.typninja.language.analysis.TypstAnalysis
import com.livteam.typninja.language.analysis.TypstProjectLabels
import com.livteam.typninja.language.analysis.TypstProjectModelService
import com.livteam.typninja.language.psi.TypstElementTypes
import com.livteam.typninja.language.psi.TypstFile
import com.livteam.typninja.language.references.TypstSymbols
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent as SwingDocumentEvent

class TypstWorkspaceToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val disposable = Disposer.newDisposable("Typst workspace view")
        val view = TypstWorkspaceView(project, disposable)
        val contentFactory = ContentFactory.getInstance()
        toolWindow.contentManager.addContent(contentFactory.createContent(view.outline, "Outline", false))
        toolWindow.contentManager.addContent(contentFactory.createContent(view.labels, "Labels", false))
        toolWindow.contentManager.addContent(contentFactory.createContent(view.symbols, "Symbols", false))
        toolWindow.contentManager.addContent(contentFactory.createContent(view.packages, "Packages", false))
        toolWindow.contentManager.addContent(contentFactory.createContent(view.fonts, "Fonts", false))
        toolWindow.contentManager.addContent(contentFactory.createContent(view.templates, "Templates", false))
        toolWindow.contentManager.addContent(contentFactory.createContent(view.summary, "Summary", false))
        Disposer.register(toolWindow.disposable, disposable)
        view.refresh()
    }
}

private class TypstWorkspaceView(
    private val project: Project,
    private val disposable: Disposable,
) {
    private val outlineList = FilteredListPanel(::openItem)
    private val labelsList = FilteredListPanel(::openItem)
    private val symbolsList = FilteredListPanel(::insertItem)
    private val packagesList = FilteredListPanel(::openItem)
    private val fontsList = FilteredListPanel(::insertItem)
    private val templatesList = FilteredListPanel(::openItem)
    private val summaryText = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }

    val outline: JPanel get() = outlineList.panel
    val labels: JPanel get() = labelsList.panel
    val symbols: JPanel get() = symbolsList.panel
    val packages: JPanel get() = packagesList.panel
    val fonts: JPanel get() = fontsList.panel
    val templates: JPanel get() = templatesList.panel
    val summary: JPanel = JPanel(BorderLayout()).apply { add(JBScrollPane(summaryText), BorderLayout.CENTER) }

    init {
        project.messageBus.connect(disposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
            },
        )
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val selectedEditor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
                    if (selectedEditor.document === event.document) refresh()
                }
            },
            disposable,
        )
        val removePackageListener = TypstProjectModelService.getInstance(project).addListener { refresh() }
        Disposer.register(disposable, Disposable(removePackageListener))
    }

    fun refresh() {
        val selectedFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        ReadAction.nonBlocking<WorkspaceViewSnapshot> { buildSnapshot(selectedFile) }
            .coalesceBy(this)
            .expireWith(disposable)
            .finishOnUiThread(ModalityState.any(), ::showSnapshot)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun buildSnapshot(selectedFile: VirtualFile?): WorkspaceViewSnapshot {
        val psiFile = selectedFile?.let { PsiManager.getInstance(project).findFile(it) } as? TypstFile
        val analysis = psiFile?.let(TypstAnalysis::snapshot)
        val outlineItems = buildList {
            analysis?.headings.orEmpty().forEach { heading ->
                add(ViewItem(heading.text.trim().lineSequence().firstOrNull().orEmpty(), selectedFile, heading.textOffset))
            }
            analysis?.declarations.orEmpty().forEach { definition ->
                add(ViewItem(definition.name, definition.navigationElement.containingFile.virtualFile, definition.navigationElement.textOffset))
            }
        }
        val labelItems = TypstProjectLabels.names(project).sorted().map { labelName ->
            val definition = TypstProjectLabels.definitions(project, labelName).firstOrNull()
            ViewItem("@$labelName", definition?.containingFile?.virtualFile, definition?.textOffset ?: 0)
        }
        val symbolItems = TypstSymbols.catalog().map { symbol ->
            ViewItem("${symbol.glyph}  ${symbol.path}", insertText = symbol.path)
        }
        val packageItems = TypstProjectModelService.getInstance(project).packageCatalog().entrypoints
            .toSortedMap()
            .map { (specification, entrypoint) -> ViewItem(specification, entrypoint, 0) }
        val fontItems = SYSTEM_FONTS.map { family -> ViewItem(family, insertText = "#set text(font: \"$family\")\n") }
        val templateItems = ArrayList<ViewItem>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && file.extension == "typ") templateItems.add(ViewItem(file.presentableName, file, 0))
            true
        }
        templateItems.sortBy { it.label.lowercase() }

        val text = psiFile?.text.orEmpty()
        val importCount = analysis?.imports?.size ?: 0
        val labelCount = analysis?.labels?.size ?: 0
        val headingCount = analysis?.headings?.size ?: 0
        val definitionCount = analysis?.declarations?.size ?: 0
        val wordCount = WORD_PATTERN.findAll(text).count()
        val resourcePaths = analysis?.pathLiterals.orEmpty().map { it.text }.distinct()
        val summary = if (psiFile == null) {
            "Open a Typst file to inspect it."
        } else buildString {
            appendLine("File: ${selectedFile?.path.orEmpty()}")
            appendLine("Lines: ${text.lineSequence().count()}")
            appendLine("Words: $wordCount")
            appendLine("Headings: $headingCount")
            appendLine("Definitions: $definitionCount")
            appendLine("Labels: $labelCount")
            appendLine("Imports: $importCount")
            if (resourcePaths.isNotEmpty()) {
                appendLine()
                appendLine("Resources:")
                resourcePaths.forEach { appendLine("• $it") }
            }
        }
        return WorkspaceViewSnapshot(outlineItems, labelItems, symbolItems, packageItems, fontItems, templateItems, summary)
    }

    private fun showSnapshot(snapshot: WorkspaceViewSnapshot) {
        outlineList.setItems(snapshot.outline)
        labelsList.setItems(snapshot.labels)
        symbolsList.setItems(snapshot.symbols)
        packagesList.setItems(snapshot.packages)
        fontsList.setItems(snapshot.fonts)
        templatesList.setItems(snapshot.templates)
        summaryText.text = snapshot.summary
        summaryText.caretPosition = 0
    }

    private fun openItem(item: ViewItem) {
        val file = item.file ?: return
        OpenFileDescriptor(project, file, item.offset.coerceAtLeast(0)).navigate(true)
    }

    private fun insertItem(item: ViewItem) {
        val insertText = item.insertText ?: return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val insertion = if (insertText.startsWith("#set ")) insertText else {
            val file = PsiManager.getInstance(project).findFile(FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return)
            val leaf = file?.findElementAt(editor.caretModel.offset.coerceAtMost((file.textLength - 1).coerceAtLeast(0)))
            val inCode = generateSequence(leaf) { it.parent }.any {
                it.node.elementType == TypstElementTypes.CODE_EXPRESSION || it.node.elementType == TypstElementTypes.CODE_BLOCK
            }
            if (inCode) insertText else "#$insertText"
        }
        WriteCommandAction.runWriteCommandAction(project, "Insert Typst value", null, {
            val start = editor.selectionModel.selectionStart
            val end = editor.selectionModel.selectionEnd
            editor.document.replaceString(start, end, insertion)
            editor.caretModel.moveToOffset(start + insertion.length)
            editor.selectionModel.removeSelection()
        })
    }

    private companion object {
        val WORD_PATTERN = Regex("[\\p{L}\\p{N}_-]+")
        val SYSTEM_FONTS: List<String> by lazy {
            runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.sorted() }
                .getOrDefault(emptyList())
        }
    }
}

private data class ViewItem(
    val label: String,
    val file: VirtualFile? = null,
    val offset: Int = 0,
    val insertText: String? = null,
) {
    override fun toString(): String = label
}

private data class WorkspaceViewSnapshot(
    val outline: List<ViewItem>,
    val labels: List<ViewItem>,
    val symbols: List<ViewItem>,
    val packages: List<ViewItem>,
    val fonts: List<ViewItem>,
    val templates: List<ViewItem>,
    val summary: String,
)

private class FilteredListPanel(private val onOpen: (ViewItem) -> Unit) {
    private val search = SearchTextField(false)
    private val model = DefaultListModel<ViewItem>()
    private val list = JBList(model).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private var allItems: List<ViewItem> = emptyList()

    val panel = JPanel(BorderLayout()).apply {
        add(search, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JLabel("Double-click to open or insert"), BorderLayout.SOUTH)
    }

    init {
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: SwingDocumentEvent) = applyFilter()
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) list.selectedValue?.let(onOpen)
            }
        })
    }

    fun setItems(items: List<ViewItem>) {
        allItems = items
        applyFilter()
    }

    private fun applyFilter() {
        val query = search.text.trim()
        model.clear()
        allItems.asSequence()
            .filter { query.isEmpty() || it.label.contains(query, ignoreCase = true) }
            .take(MAX_VISIBLE_ITEMS)
            .forEach(model::addElement)
    }

    private companion object {
        const val MAX_VISIBLE_ITEMS = 5_000
    }
}
