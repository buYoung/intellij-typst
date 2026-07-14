package com.livteam.typninja.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import com.livteam.typninja.language.TypstFileType
import com.livteam.typninja.settings.TypstSettingsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class TypstPreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val previewPanel = TypstPreviewPanel(project)
        val content = ContentFactory.getInstance().createContent(previewPanel.component, "Preview", false)
        Disposer.register(content, previewPanel)
        toolWindow.contentManager.addContent(content)
    }
}

private class TypstPreviewPanel(private val project: Project) : Disposable {
    private val pagePanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val scrollPane = JBScrollPane(pagePanel)
    private val statusLabel = JLabel("Open a Typst file and choose Preview.")
    private val zoomLabel = JLabel("100%")
    private val invertCheckBox = JCheckBox("Invert colors")
    private val renderGeneration = AtomicLong()
    private var latestResult: TypstPreviewResult? = null
    private var originalImages: List<BufferedImage> = emptyList()
    private var pageLabels: List<JLabel> = emptyList()
    private var zoom = 1.0
    private var currentPage = 0
    private var removeServiceListener: (() -> Unit)? = null

    val component = JPanel(BorderLayout()).apply {
        add(buildToolbar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    init {
        invertCheckBox.isSelected = TypstSettingsService.getInstance(project).state.invertPreviewColors
        removeServiceListener = TypstPreviewService.getInstance(project).addListener(::acceptResult)
        EditorFactory.getInstance().eventMulticaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = syncPreviewToCaret(event)
            },
            this,
        )
    }

    private fun buildToolbar(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        add(JButton("Refresh").apply { addActionListener { refresh() } })
        add(JButton("Previous").apply { addActionListener { showPage(currentPage - 1) } })
        add(JButton("Next").apply { addActionListener { showPage(currentPage + 1) } })
        add(JButton("−").apply { addActionListener { setZoom(zoom - 0.1) } })
        add(zoomLabel)
        add(JButton("+").apply { addActionListener { setZoom(zoom + 0.1) } })
        add(JButton("Fit width").apply { addActionListener { fitWidth() } })
        add(invertCheckBox.apply {
            addActionListener {
                TypstSettingsService.getInstance(project).state.invertPreviewColors = isSelected
                renderImages()
            }
        })
    }

    private fun refresh() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        if (file.fileType != TypstFileType) return
        val text = FileDocumentManager.getInstance().getDocument(file)?.text
        TypstPreviewService.getInstance(project).preview(file, text)
    }

    private fun acceptResult(result: TypstPreviewResult) {
        latestResult = result
        when {
            result.isRunning -> statusLabel.text = "Rendering…"
            result.failureMessage != null -> statusLabel.text = result.failureMessage
            result.outputFiles.isEmpty() -> statusLabel.text = "No preview pages were produced."
            result.format != "png" -> statusLabel.text = "Created ${result.outputFile?.path.orEmpty()}"
            else -> loadImages(result)
        }
    }

    private fun loadImages(result: TypstPreviewResult) {
        val generation = renderGeneration.incrementAndGet()
        val outputPaths = result.outputFiles.map { it.toNioPath() }
        AppExecutorUtil.getAppExecutorService().submit {
            val images = outputPaths.mapNotNull { path -> runCatching { ImageIO.read(path.toFile()) }.getOrNull() }
            ApplicationManager.getApplication().invokeLater {
                if (renderGeneration.get() != generation || Disposer.isDisposed(this)) return@invokeLater
                originalImages = images
                currentPage = currentPage.coerceIn(0, (images.size - 1).coerceAtLeast(0))
                renderImages()
                val duration = result.durationMillis?.let { " in ${it}ms" }.orEmpty()
                statusLabel.text = "${images.size} page${if (images.size == 1) "" else "s"}$duration"
            }
        }
    }

    private fun renderImages() {
        pagePanel.removeAll()
        val invert = invertCheckBox.isSelected
        pageLabels = originalImages.mapIndexed { index, source ->
            val displaySource = if (invert) invert(source) else source
            val width = (displaySource.width * zoom).toInt().coerceAtLeast(1)
            val height = (displaySource.height * zoom).toInt().coerceAtLeast(1)
            val scaled = displaySource.getScaledInstance(width, height, Image.SCALE_SMOOTH)
            JLabel("Page ${index + 1}", ImageIcon(scaled), SwingConstants.CENTER).apply {
                horizontalTextPosition = SwingConstants.CENTER
                verticalTextPosition = SwingConstants.TOP
                alignmentX = 0.5f
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(8, 8, 8, 8),
                    BorderFactory.createLineBorder(JBColor.border()),
                )
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        currentPage = index
                        syncEditorToPage(index)
                    }
                })
            }
        }
        pageLabels.forEach(pagePanel::add)
        zoomLabel.text = "${(zoom * 100).toInt()}%"
        pagePanel.revalidate()
        pagePanel.repaint()
        showPage(currentPage)
    }

    private fun setZoom(value: Double) {
        zoom = value.coerceIn(0.2, 3.0)
        renderImages()
    }

    private fun fitWidth() {
        val widest = originalImages.maxOfOrNull(BufferedImage::getWidth) ?: return
        val available = (scrollPane.viewport.extentSize.width - 24).coerceAtLeast(1)
        setZoom(available.toDouble() / widest)
    }

    private fun showPage(index: Int) {
        if (pageLabels.isEmpty()) return
        currentPage = index.coerceIn(pageLabels.indices)
        pageLabels[currentPage].scrollRectToVisible(pageLabels[currentPage].bounds)
    }

    private fun syncPreviewToCaret(event: CaretEvent) {
        val result = latestResult ?: return
        val source = result.sourceFile ?: return
        val selectedFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        if (selectedFile != source || pageLabels.isEmpty()) return
        val document = event.editor.document
        val fraction = event.caret.logicalPosition.line.toDouble() / document.lineCount.coerceAtLeast(1)
        showPage((fraction * pageLabels.size).toInt().coerceAtMost(pageLabels.lastIndex))
    }

    private fun syncEditorToPage(pageIndex: Int) {
        val result = latestResult ?: return
        val source = result.sourceFile ?: return
        val document = FileDocumentManager.getInstance().getDocument(source) ?: return
        val fraction = pageIndex.toDouble() / pageLabels.size.coerceAtLeast(1)
        val line = (fraction * document.lineCount).toInt().coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
        OpenFileDescriptor(project, source, document.getLineStartOffset(line)).navigate(true)
    }

    private fun invert(source: BufferedImage): BufferedImage {
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val value = source.getRGB(x, y)
                val alpha = value ushr 24 and 0xff
                val red = 255 - (value ushr 16 and 0xff)
                val green = 255 - (value ushr 8 and 0xff)
                val blue = 255 - (value and 0xff)
                target.setRGB(x, y, alpha shl 24 or (red shl 16) or (green shl 8) or blue)
            }
        }
        return target
    }

    override fun dispose() {
        renderGeneration.incrementAndGet()
        removeServiceListener?.invoke()
        removeServiceListener = null
        originalImages = emptyList()
        pageLabels = emptyList()
    }
}
