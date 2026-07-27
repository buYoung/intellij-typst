package com.livteam.typninja.preview

import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.livteam.typninja.runtime.TypstRuntimeService
import com.livteam.typninja.runtime.TypstRuntimeSourcePosition
import com.livteam.typninja.language.TypstFileType
import com.livteam.typninja.settings.TypstSettingsService
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class TypstPreviewPanel(
    private val project: Project,
    private val editorFile: VirtualFile,
    initialBinding: TypstPreviewBinding,
) : Disposable {
    private var sourceFile = initialBinding.previewSource
    private var pendingPreviewSelection = initialBinding.selection
    private val browser = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val previewLinkQuery = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val previewPositionQuery = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val previewLoadHandler = browser?.let {
        val linkQuery = checkNotNull(previewLinkQuery)
        val positionQuery = checkNotNull(previewPositionQuery)
        object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                frame.executeJavaScript(previewLinkBridgeScript(linkQuery), frame.url, 0)
                frame.executeJavaScript(previewPositionBridgeScript(positionQuery), frame.url, 0)
            }
        }
    }
    private val bridgeInjectionAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val previewComponent = browser?.component ?: JLabel(
        "Typst preview requires JCEF in the IDE runtime.",
        SwingConstants.CENTER,
    )
    private val statusLabel = JLabel("Open a Typst file and choose Preview.")
    private val zoomLabel = JLabel("100%")
    private val invertCheckBox = JCheckBox("Invert colors")
    private val renderGeneration = AtomicLong()
    private var zoom = 1.0
    private var currentPage = 0
    private var pageCount = 0
    private var currentPreviewUrl: String? = null
    private var mouseSelectionBeforePress: MouseSelection? = null
    @Volatile
    private var sourceMappingContext: SourceMappingContext? = null
    @Volatile
    private var isDisposed = false
    private var hasRequestedInitialPreview = false
    private var hasDeselectedBoundPreview = false
    private var removeServiceListener: (() -> Unit)? = null
    private var removeBindingListener: (() -> Unit)? = null

    val component = object : JPanel(BorderLayout()) {
        init {
            add(buildToolbar(), BorderLayout.NORTH)
            add(previewComponent, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }

        override fun addNotify() {
            super.addNotify()
            onAdded()
        }
    }

    init {
        Disposer.register(project, this)
        previewLinkQuery?.addHandler(::openPreviewLink)
        previewPositionQuery?.addHandler(::openPreviewPosition)
        if (browser != null && previewLoadHandler != null) {
            browser.jbCefClient.addLoadHandler(previewLoadHandler, browser.cefBrowser)
        }
        invertCheckBox.isSelected = TypstSettingsService.getInstance(project).state.invertPreviewColors
        removeServiceListener = TypstPreviewService.getInstance(project).addListener(::acceptResult)
        removeBindingListener = project.service<TypstPreviewBindingService>().addListener(editorFile) { binding ->
            val hasSourceChanged = binding.previewSource != sourceFile
            sourceFile = binding.previewSource
            pendingPreviewSelection = binding.selection
            hasDeselectedBoundPreview = false
            if (hasSourceChanged) {
                sourceMappingContext = null
                showLatestOrRefresh()
            } else {
                showPendingPreviewSelection()
            }
        }
        EditorFactory.getInstance().eventMulticaster.addEditorMouseListener(
            object : EditorMouseListener {
                override fun mousePressed(event: EditorMouseEvent) {
                    mouseSelectionBeforePress = selectionOf(event.editor)
                }

                override fun mouseReleased(event: EditorMouseEvent) {
                    val selection = selectionOf(event.editor)
                    val previous = mouseSelectionBeforePress
                    mouseSelectionBeforePress = null
                    if (selection != null && selection != previous) syncSourceSelection(event.editor, selection)
                }
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
                executeJavaScript("window.typstPreview?.setInvert(${isSelected})")
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

    fun showLatestOrRefresh() {
        if (isDisposed) return
        val previewService = TypstPreviewService.getInstance(project)
        val existingResult = previewService.statusFor(sourceFile)
        val successfulResult = previewService.lastSuccessfulFor(sourceFile)
        when {
            existingResult == null -> refresh()
            existingResult.isRunning -> {
                successfulResult?.let(::acceptResult)
                refresh()
            }
            existingResult.failureMessage != null -> {
                successfulResult?.let(::acceptResult)
                acceptResult(existingResult)
            }
            else -> acceptResult(existingResult)
        }
    }

    fun requestInitialPreview() {
        if (isDisposed || hasRequestedInitialPreview) return
        hasRequestedInitialPreview = true
        showLatestOrRefresh()
    }

    fun onAdded() {
        requestInitialPreview()
        scheduleBridgeInjection()
        showPendingPreviewSelection()
    }

    fun onSelected() {
        if (hasDeselectedBoundPreview && sourceFile != editorFile) {
            sourceFile = editorFile
            pendingPreviewSelection = null
            sourceMappingContext = null
            hasDeselectedBoundPreview = false
            showLatestOrRefresh()
        }
        onAdded()
    }

    fun onDeselected() {
        if (sourceFile != editorFile) hasDeselectedBoundPreview = true
    }

    private fun acceptResult(result: TypstPreviewResult) {
        if (result.sourceFile != sourceFile) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed) acceptResult(result)
            }
            return
        }
        when {
            result.isRunning -> {
                renderGeneration.incrementAndGet()
                statusLabel.text = "Rendering…"
            }
            result.failureMessage != null -> statusLabel.text = result.failureMessage
            browser == null -> statusLabel.text = "Typst preview requires JCEF in the IDE runtime."
            result.previewUrl != null -> showRemotePreview(result)
            result.outputFiles.isEmpty() -> statusLabel.text = "No preview pages were produced."
            result.format == "svg" -> loadSvgPreview(result)
            else -> statusLabel.text = "Unsupported preview format: ${result.format}"
        }
    }

    private fun showRemotePreview(result: TypstPreviewResult) {
        sourceMappingContext = if (result.sourceMappingAvailable &&
            result.runtimeGeneration != null && result.documentVersion != null
        ) {
            SourceMappingContext(result.runtimeGeneration, result.documentVersion)
        } else {
            null
        }
        pageCount = result.pageCount
        currentPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        renderGeneration.incrementAndGet()
        val previewUrl = result.previewUrl ?: return
        if (currentPreviewUrl == previewUrl) {
            executeJavaScript("window.typstPreview?.refresh();window.typstPreview?.setInvert(${invertCheckBox.isSelected})")
            previewPositionQuery?.let { query -> executeJavaScript(previewPositionBridgeScript(query)) }
            showPendingPreviewSelection()
        } else {
            currentPreviewUrl = previewUrl
            browser?.loadURL(previewUrl)
            scheduleBridgeInjection()
        }
        updatePreviewStatus(result)
    }

    private fun loadSvgPreview(result: TypstPreviewResult) {
        sourceMappingContext = null
        currentPreviewUrl = null
        val generation = renderGeneration.incrementAndGet()
        val outputPaths = result.outputFiles.map { it.toNioPath() }
        AppExecutorUtil.getAppExecutorService().submit {
            val pages = outputPaths.mapNotNull { path -> runCatching { Files.readString(path) }.getOrNull() }
            val html = buildSvgPreviewHtml(pages)
            ApplicationManager.getApplication().invokeLater {
                if (renderGeneration.get() != generation || isDisposed) return@invokeLater
                pageCount = pages.size
                currentPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                browser?.loadHTML(html)
                updatePreviewStatus(result)
            }
        }
    }

    private fun updatePreviewStatus(result: TypstPreviewResult) {
        val duration = result.durationMillis?.let { " in ${it}ms" }.orEmpty()
        statusLabel.text = "$pageCount page${if (pageCount == 1) "" else "s"}$duration"
    }

    private fun setZoom(value: Double) {
        zoom = value.coerceIn(MIN_PREVIEW_SCALE, MAX_PREVIEW_SCALE)
        zoomLabel.text = "${(zoom * 100).toInt()}%"
        executeJavaScript("window.typstPreview?.setScale($zoom)")
    }

    private fun fitWidth() {
        zoom = MAX_PREVIEW_SCALE
        zoomLabel.text = "Fit"
        executeJavaScript("window.typstPreview?.setScale($MAX_PREVIEW_SCALE)")
    }

    private fun showPage(index: Int) {
        if (pageCount == 0) return
        currentPage = index.coerceIn(0, pageCount - 1)
        executeJavaScript("window.typstPreview?.showPage(${currentPage + 1})")
    }

    private fun buildSvgPreviewHtml(pages: List<String>): String {
        val body = pages.mapIndexed { index, svg ->
            "<section class=\"page\" data-page=\"${index + 1}\">$svg</section>"
        }.joinToString("")
        return """
            <!doctype html><meta charset="utf-8"><style>
            :root { color-scheme: light dark } html,body { max-width:100%;overflow-x:hidden }
            body { margin:0;background:#777 }
            #pages { --preview-scale:1;display:grid;gap:16px;padding:16px;box-sizing:border-box;width:100%;justify-items:center }
            .page { width:calc(100% * var(--preview-scale));max-width:100%;background:white;box-shadow:0 2px 12px #0006 }
            .page svg { display:block;width:100%;max-width:100%;height:auto }
            body.invert .page { filter:invert(1) hue-rotate(180deg) }
            </style><div id="pages">$body</div><script>
            let scale=1; const pages=document.getElementById('pages');
            const save=()=>sessionStorage.setItem('typst-preview-state',JSON.stringify(window.typstPreview.state()));
            window.typstPreview={
              setScale:v=>{scale=Math.max(.2,Math.min(1,Number(v)||1));pages.style.setProperty('--preview-scale',scale);save()},
              setInvert:v=>{document.body.classList.toggle('invert',!!v);save()},
              showPage:n=>document.querySelector('.page[data-page="'+n+'"]')?.scrollIntoView({block:'start'}),
              state:()=>({scale,scrollY:window.scrollY}),
              restore:s=>{if(s){window.typstPreview.setScale(s.scale||1);scrollTo(0,s.scrollY||0)}}
            };
            addEventListener('scroll',save,{passive:true});
            try{window.typstPreview.restore(JSON.parse(sessionStorage.getItem('typst-preview-state')))}catch(_e){}
            window.typstPreview.setInvert(${invertCheckBox.isSelected});
            </script>
        """.trimIndent()
    }

    private fun executeJavaScript(script: String) {
        val cefBrowser = browser?.cefBrowser ?: return
        cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
    }

    private fun scheduleBridgeInjection() {
        if (currentPreviewUrl == null) return
        bridgeInjectionAlarm.cancelAllRequests()
        for (delayMillis in BRIDGE_INJECTION_DELAYS_MILLIS) {
            bridgeInjectionAlarm.addRequest(
                {
                    if (isDisposed || browser?.cefBrowser?.url != currentPreviewUrl) return@addRequest
                    previewLinkQuery?.let { executeJavaScript(previewLinkBridgeScript(it)) }
                    previewPositionQuery?.let { executeJavaScript(previewPositionBridgeScript(it)) }
                    showPendingPreviewSelection()
                },
                delayMillis,
            )
        }
    }

    private fun showPendingPreviewSelection() {
        val selection = pendingPreviewSelection ?: return
        executeJavaScript(
            "window.typstPreview?.showSelection(${selection.token},${selection.page},${selection.x},${selection.y})",
        )
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

    private fun openPreviewPosition(payload: String): JBCefJSQuery.Response {
        val context = sourceMappingContext ?: return JBCefJSQuery.Response("")
        val position = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
            ?: return JBCefJSQuery.Response("")
        val page = position.get("page")?.asInt ?: return JBCefJSQuery.Response("")
        val x = position.get("x")?.asDouble ?: return JBCefJSQuery.Response("")
        val y = position.get("y")?.asDouble ?: return JBCefJSQuery.Response("")
        TypstRuntimeService.getInstance(project).requestDocumentToSource(
            source = sourceFile,
            documentVersion = context.documentVersion,
            runtimeGeneration = context.runtimeGeneration,
            page = page,
            x = x,
            y = y,
        ) { mappedPosition ->
            if (!isDisposed && sourceMappingContext == context) {
                navigateToSource(mappedPosition, page, x, y)
            }
        }
        return JBCefJSQuery.Response("")
    }

    private fun navigateToSource(
        position: TypstRuntimeSourcePosition,
        page: Int,
        x: Double,
        y: Double,
    ) {
        val file = VirtualFileManager.getInstance().findFileByUrl(position.uri) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        if (document.lineCount == 0) return
        val startOffset = sourceOffset(document, position.line, position.column)
        val endOffset = sourceOffset(document, position.endLine, position.endColumn).coerceAtLeast(startOffset)
        val bindingService = project.service<TypstPreviewBindingService>()
        val binding = bindingService.bind(file, sourceFile, page, x, y)
        pendingPreviewSelection = binding.selection
        showPendingPreviewSelection()
        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, file, startOffset), true)
        bindingService.completeNavigation(file, binding)
        editor ?: return
        editor.selectionModel.setSelection(startOffset, endOffset)
        editor.caretModel.moveToOffset(endOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun sourceOffset(document: com.intellij.openapi.editor.Document, line: Int, column: Int): Int {
        val safeLine = line.coerceIn(0, document.lineCount - 1)
        return (document.getLineStartOffset(safeLine) + column.coerceAtLeast(0))
            .coerceAtMost(document.getLineEndOffset(safeLine))
    }

    private fun selectionOf(editor: Editor): MouseSelection? {
        if (editor.project != project || editor.isDisposed || editor.virtualFile?.fileType != TypstFileType) return null
        if (FileEditorManager.getInstance(project).selectedTextEditor !== editor) return null
        val file = editor.virtualFile ?: return null
        val caret = editor.caretModel.primaryCaret
        return MouseSelection(file, caret.selectionStart, caret.selectionEnd, caret.offset)
    }

    private fun syncSourceSelection(editor: Editor, selection: MouseSelection) {
        val context = sourceMappingContext ?: return
        val document = editor.document
        val offset = selection.caretOffset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(line)
        TypstRuntimeService.getInstance(project).requestSourceToDocument(
            source = sourceFile,
            documentVersion = context.documentVersion,
            runtimeGeneration = context.runtimeGeneration,
            position = TypstRuntimeSourcePosition(selection.file.url, line, column),
        ) { positions ->
            if (isDisposed || sourceMappingContext != context) return@requestSourceToDocument
            val payload = positions.joinToString(prefix = "[", postfix = "]") {
                "{page:${it.page},x:${it.x},y:${it.y}}"
            }
            executeJavaScript("window.typstPreview?.showPositions($payload)")
        }
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
                if (!href) return;
                event.preventDefault();
                if (href.startsWith('#')) return;
                event.stopImmediatePropagation();
                if (!/^https?:\/\//i.test(href)) return;
                const resolvedUrl = new URL(href, window.location.href).href;
                $openExternalLink
              }, true);
            })();
        """.trimIndent()
    }

    private fun previewPositionBridgeScript(query: JBCefJSQuery): String {
        val mapPosition = query.inject("payload")
        return """
            (() => {
              if (window.__typstPreviewPositionBridgeInstalled) return;
              window.__typstPreviewPositionBridgeInstalled = true;
              let pointerStart = null;
              document.addEventListener('pointerdown', event => {
                pointerStart = {x:event.clientX,y:event.clientY};
              }, true);
              document.addEventListener('click', event => {
                if (pointerStart && Math.hypot(event.clientX-pointerStart.x,event.clientY-pointerStart.y) > 4) {
                  pointerStart = null;
                  event.preventDefault();
                  event.stopImmediatePropagation();
                  return;
                }
                pointerStart = null;
                if (event.target.closest?.('a,[data-target-page]')) return;
                const page = event.target.closest?.('.page');
                const svg = page?.querySelector?.('svg');
                const matrix = svg?.getScreenCTM?.();
                const pageNumber = Number(page?.dataset?.page);
                if (!svg || !matrix || !Number.isInteger(pageNumber) || pageNumber < 1) return;
                let inverse;
                try { inverse = matrix.inverse(); } catch (_error) { return; }
                const point = svg.createSVGPoint();
                point.x = event.clientX;
                point.y = event.clientY;
                const local = point.matrixTransform(inverse);
                if (!Number.isFinite(local.x) || !Number.isFinite(local.y)) return;
                event.preventDefault();
                const payload = JSON.stringify({page:pageNumber,x:local.x,y:local.y});
                $mapPosition
              }, true);
            })();
        """.trimIndent()
    }

    override fun dispose() {
        isDisposed = true
        renderGeneration.incrementAndGet()
        removeServiceListener?.invoke()
        removeServiceListener = null
        removeBindingListener?.invoke()
        removeBindingListener = null
        if (browser != null && previewLoadHandler != null) {
            browser.jbCefClient.removeLoadHandler(previewLoadHandler, browser.cefBrowser)
        }
        previewLinkQuery?.dispose()
        previewPositionQuery?.dispose()
        browser?.dispose()
    }

    private data class SourceMappingContext(
        val runtimeGeneration: Long,
        val documentVersion: Long,
    )

    private data class MouseSelection(
        val file: VirtualFile,
        val startOffset: Int,
        val endOffset: Int,
        val caretOffset: Int,
    )

    private companion object {
        const val MIN_PREVIEW_SCALE = 0.2
        const val MAX_PREVIEW_SCALE = 1.0
        val BRIDGE_INJECTION_DELAYS_MILLIS = intArrayOf(100, 500, 1_500, 3_000, 5_000, 10_000, 20_000)
    }
}

internal class TypstPreviewFileEditor(
    project: Project,
    private val editorFile: VirtualFile,
    binding: TypstPreviewBinding,
) : UserDataHolderBase(), FileEditor {
    private val previewPanel = TypstPreviewPanel(project, editorFile, binding)
    private val propertyChangeSupport = PropertyChangeSupport(this)

    override fun getComponent() = previewPanel.component

    override fun getPreferredFocusedComponent() = previewPanel.component

    override fun getName(): String = "Preview"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = editorFile.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeSupport.removePropertyChangeListener(listener)
    }

    override fun getFile(): VirtualFile = editorFile

    override fun selectNotify() {
        previewPanel.onSelected()
    }

    override fun deselectNotify() {
        previewPanel.onDeselected()
    }

    override fun dispose() {
        Disposer.dispose(previewPanel)
    }
}

@Service(Service.Level.PROJECT)
internal class TypstPreviewBindingService : Disposable {
    private val bindings = ConcurrentHashMap<String, TypstPreviewBinding>()
    private val listeners = ConcurrentHashMap<String, MutableSet<(TypstPreviewBinding) -> Unit>>()
    private val selectionSequence = AtomicLong()

    fun bindingFor(editorFile: VirtualFile): TypstPreviewBinding =
        bindings.remove(editorFile.url)?.takeIf { it.previewSource.isValid }
            ?: TypstPreviewBinding(editorFile)

    fun bind(
        editorFile: VirtualFile,
        previewSource: VirtualFile,
        page: Int,
        x: Double,
        y: Double,
    ): TypstPreviewBinding {
        if (!editorFile.isValid || !previewSource.isValid) return bindingFor(editorFile)
        val binding = TypstPreviewBinding(
            previewSource,
            TypstPreviewSelection(selectionSequence.incrementAndGet(), page, x, y),
        )
        bindings[editorFile.url] = binding
        listeners[editorFile.url]?.toList()?.forEach { listener -> listener(binding) }
        return binding
    }

    fun completeNavigation(editorFile: VirtualFile, binding: TypstPreviewBinding) {
        bindings.remove(editorFile.url, binding)
    }

    fun addListener(editorFile: VirtualFile, listener: (TypstPreviewBinding) -> Unit): () -> Unit {
        val fileListeners = listeners.computeIfAbsent(editorFile.url) { ConcurrentHashMap.newKeySet() }
        fileListeners.add(listener)
        return {
            fileListeners.remove(listener)
            if (fileListeners.isEmpty()) listeners.remove(editorFile.url, fileListeners)
        }
    }

    override fun dispose() {
        bindings.clear()
        listeners.clear()
    }
}

internal data class TypstPreviewBinding(
    val previewSource: VirtualFile,
    val selection: TypstPreviewSelection? = null,
)

internal data class TypstPreviewSelection(
    val token: Long,
    val page: Int,
    val x: Double,
    val y: Double,
)
