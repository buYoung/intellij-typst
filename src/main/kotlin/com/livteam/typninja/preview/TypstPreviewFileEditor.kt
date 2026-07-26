package com.livteam.typninja.preview

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import com.livteam.typninja.settings.TypstSettingsService
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

internal class TypstPreviewPanel(
    private val project: Project,
    private val sourceFile: VirtualFile,
) : Disposable {
    private val pagePanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val scrollPane = JBScrollPane(pagePanel)
    private val browser = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val previewLinkQuery = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val previewLoadHandler = previewLinkQuery?.let { query ->
        object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) frame.executeJavaScript(previewLinkBridgeScript(query), frame.url, 0)
            }
        }
    }
    private val previewLayout = CardLayout()
    private val previewContainer = JPanel(previewLayout).apply {
        add(scrollPane, IMAGE_PREVIEW_CARD)
        browser?.component?.let { add(it, BROWSER_PREVIEW_CARD) }
        previewLayout.show(this, IMAGE_PREVIEW_CARD)
    }
    private val statusLabel = JLabel("Open a Typst file and choose Preview.")
    private val zoomLabel = JLabel("100%")
    private val invertCheckBox = JCheckBox("Invert colors")
    private val renderGeneration = AtomicLong()
    private var latestResult: TypstPreviewResult? = null
    private var originalImages: List<BufferedImage> = emptyList()
    private var pageLabels: List<JLabel> = emptyList()
    private var zoom = 1.0
    private var currentPage = 0
    private var isBrowserPreviewVisible = false
    @Volatile
    private var isDisposed = false
    private var removeServiceListener: (() -> Unit)? = null

    val component = JPanel(BorderLayout()).apply {
        add(buildToolbar(), BorderLayout.NORTH)
        add(previewContainer, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    init {
        previewLinkQuery?.addHandler(::openPreviewLink)
        if (browser != null && previewLoadHandler != null) {
            browser.jbCefClient.addLoadHandler(previewLoadHandler, browser.cefBrowser)
        }
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (!isDisposed && !isBrowserPreviewVisible && originalImages.isNotEmpty()) renderImages()
            }
        })
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
                if (isBrowserPreviewVisible) executeJavaScript("window.typstPreview?.setInvert(${isSelected})")
                else renderImages()
            }
        })
    }

    fun refresh() {
        val document = FileDocumentManager.getInstance().getDocument(sourceFile)
        TypstPreviewService.getInstance(project).preview(
            sourceFile,
            document?.text,
            document?.modificationStamp ?: sourceFile.modificationStamp,
        )
    }

    private fun acceptResult(result: TypstPreviewResult) {
        if (result.sourceFile != sourceFile) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed) acceptResult(result)
            }
            return
        }
        latestResult = result
        when {
            result.isRunning -> {
                renderGeneration.incrementAndGet()
                statusLabel.text = "Rendering…"
            }
            result.failureMessage != null -> statusLabel.text = result.failureMessage
            result.previewUrl != null && browser != null -> {
                showBrowserPreview(result.previewUrl)
                statusLabel.text = "${result.format.uppercase()} preview${result.durationMillis?.let { " in ${it}ms" }.orEmpty()}"
            }
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
                if (renderGeneration.get() != generation || isDisposed) return@invokeLater
                originalImages = images
                currentPage = currentPage.coerceIn(0, (images.size - 1).coerceAtLeast(0))
                showImagePreview()
                renderImages()
                val duration = result.durationMillis?.let { " in ${it}ms" }.orEmpty()
                statusLabel.text = "${images.size} page${if (images.size == 1) "" else "s"}$duration"
            }
        }
    }

    private fun renderImages() {
        pagePanel.removeAll()
        val invert = invertCheckBox.isSelected
        val availableWidth = (scrollPane.viewport.extentSize.width - PREVIEW_HORIZONTAL_PADDING).coerceAtLeast(1)
        pageLabels = originalImages.mapIndexed { index, source ->
            val displaySource = if (invert) invert(source) else source
            val fittedWidth = displaySource.width.coerceAtMost(availableWidth)
            val width = (fittedWidth * zoom).toInt().coerceAtLeast(1)
            val height = (displaySource.height.toDouble() * width / displaySource.width)
                .toInt()
                .coerceAtLeast(1)
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
        zoom = value.coerceIn(MIN_PREVIEW_SCALE, MAX_PREVIEW_SCALE)
        if (isBrowserPreviewVisible) {
            zoomLabel.text = "${(zoom * 100).toInt()}%"
            executeJavaScript("window.typstPreview?.setScale($zoom)")
        } else {
            renderImages()
        }
    }

    private fun fitWidth() {
        if (isBrowserPreviewVisible) {
            zoom = MAX_PREVIEW_SCALE
            zoomLabel.text = "Fit"
            executeJavaScript("window.typstPreview?.setScale($MAX_PREVIEW_SCALE)")
            return
        }
        setZoom(MAX_PREVIEW_SCALE)
    }

    private fun showBrowserPreview(url: String) {
        val activeBrowser = browser ?: return
        renderGeneration.incrementAndGet()
        previewLayout.show(previewContainer, BROWSER_PREVIEW_CARD)
        isBrowserPreviewVisible = true
        activeBrowser.loadURL(url)
    }

    private fun showImagePreview() {
        previewLayout.show(previewContainer, IMAGE_PREVIEW_CARD)
        isBrowserPreviewVisible = false
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
        if (selectedFile != source || !result.sourceMappingAvailable) return
    }

    private fun syncEditorToPage(pageIndex: Int) {
        val result = latestResult ?: return
        if (!result.sourceMappingAvailable) return
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

    private fun executeJavaScript(script: String) {
        val cefBrowser = browser?.cefBrowser ?: return
        cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
    }

    private fun openPreviewLink(url: String): JBCefJSQuery.Response {
        val uri = runCatching { URI(url) }.getOrNull() ?: return JBCefJSQuery.Response("")
        if (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed) BrowserUtil.browse(uri)
            }
        }
        return JBCefJSQuery.Response("")
    }

    private fun previewLinkBridgeScript(query: JBCefJSQuery): String {
        val openExternalLink = query.inject("resolvedUrl")
        return """
            (() => {
              if (window.__typstPreviewLinkBridgeInstalled) return;
              window.__typstPreviewLinkBridgeInstalled = true;
              document.addEventListener('click', event => {
                const link = event.target.closest?.('a');
                if (!link) return;
                const href = link.getAttribute('href') ||
                  link.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
                if (!href || href.startsWith('#')) return;
                event.preventDefault();
                event.stopPropagation();
                if (!/^https?:\/\//i.test(href)) return;
                const resolvedUrl = new URL(href, window.location.href).href;
                $openExternalLink
              }, true);
            })();
        """.trimIndent()
    }

    override fun dispose() {
        isDisposed = true
        renderGeneration.incrementAndGet()
        removeServiceListener?.invoke()
        removeServiceListener = null
        originalImages = emptyList()
        pageLabels = emptyList()
        if (browser != null && previewLoadHandler != null) {
            browser.jbCefClient.removeLoadHandler(previewLoadHandler, browser.cefBrowser)
        }
        previewLinkQuery?.dispose()
        browser?.dispose()
    }

    private companion object {
        const val IMAGE_PREVIEW_CARD = "image"
        const val BROWSER_PREVIEW_CARD = "browser"
        const val PREVIEW_HORIZONTAL_PADDING = 24
        const val MIN_PREVIEW_SCALE = 0.2
        const val MAX_PREVIEW_SCALE = 1.0
    }
}

internal class TypstPreviewFileEditor(
    project: Project,
    private val sourceFile: VirtualFile,
) : UserDataHolderBase(), FileEditor {
    private val previewPanel = TypstPreviewPanel(project, sourceFile)
    private val propertyChangeSupport = PropertyChangeSupport(this)

    override fun getComponent() = previewPanel.component

    override fun getPreferredFocusedComponent() = previewPanel.component

    override fun getName(): String = "Preview"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = sourceFile.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }

    override fun getFile(): VirtualFile = sourceFile

    override fun selectNotify() {
        previewPanel.refresh()
    }

    override fun dispose() {
        previewPanel.dispose()
    }
}
