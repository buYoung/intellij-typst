package com.livteam.typninja.preview

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.FileEditorManager
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.VirtualFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.jcef
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.driver.model.LockSemantics
import com.intellij.driver.model.OnDispatcher
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class TypstPreviewE2ETest {
    @Test
    fun `preview renders after switching entrypoints and click navigates to included source`() {
        val projectPath = Path.of(requireNotNull(System.getProperty("typst.test.project.path")))
        val pluginPath = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")))
        Starter.newContext(
            "typst-preview-entrypoint-switch",
            TestCase(IdeProductProvider.IU, LocalProjectInfo(projectPath)).withVersion("2025.2.6.2"),
        ).apply {
            PluginConfigurator(this).installPluginFromPath(pluginPath)
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForProjectOpen(2.minutes)
            val project = singleProject()
            val openedProjectPath = Path.of(project.getBasePath())
            configureSamplePackage(project, openedProjectPath)

            openFile(project, openedProjectPath.resolve("samples/verify/03-modules.typ"))
            val modulesPreviewUrl = waitForRenderedPreview(project, "03-modules.typ")
            waitForPreviewPage(modulesPreviewUrl)

            openFile(project, openedProjectPath.resolve("samples/verify/01-code.typ"))
            val firstPreviewUrl = waitForRenderedPreview(project, "01-code.typ")
            waitForPreviewPage(firstPreviewUrl)

            openFile(project, openedProjectPath.resolve("samples/verify.typ"))
            val verifyPreviewUrl = waitForRenderedPreview(project, "verify.typ")
            assertTrue(
                verifyPreviewUrl != firstPreviewUrl,
                "Switching entrypoints must load the new runtime preview URL",
            )
            waitForPreviewPage(verifyPreviewUrl)

            clickPreviewPosition(project, 1, 60.0, 380.0, "samples/verify/01-code.typ")
            waitForPreviewPage(verifyPreviewUrl)
            val firstSelectionToken = waitForPreviewSelection()
            clickPreviewPosition(project, 1, 260.0, 740.0, "samples/verify/04-math.typ")
            waitForPreviewPage(verifyPreviewUrl)
            waitForPreviewSelection(firstSelectionToken)

            val verifyFile = findFile(openedProjectPath.resolve("samples/verify.typ"))
            service(TypstPreviewServiceRef::class, project).preview(
                verifyFile,
                "#let =",
                9_999_999L,
            )
            waitForPreviewFailure(project, verifyFile)
            waitForPreviewPage(verifyPreviewUrl)
        }
    }

    private fun Driver.configureSamplePackage(project: Project, projectPath: Path) {
        val settings = service(TypstSettingsServiceRef::class, project)
        settings.getState().apply {
            setPackagePath(projectPath.resolve("samples/verify/packages").toString())
            setAutoDownloadPackages(false)
            setUseNativeRenderer(true)
        }
    }

    private fun Driver.openFile(project: Project, path: Path) {
        val file = findFile(path)
        withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
            service(FileEditorManager::class, project).openFile(file, true, true)
        }
    }

    private fun Driver.findFile(path: Path): VirtualFile = requireNotNull(
        utility(LocalFileSystemRef::class).getInstance().refreshAndFindFileByPath(path.toString()),
    ) {
        "Test file is not visible in the IDE VFS: $path"
    }

    private fun Driver.waitForRenderedPreview(project: Project, fileName: String): String {
        val previewService = service(TypstPreviewServiceRef::class, project)
        val fileEditorManager = service(FileEditorManager::class, project)
        val result = waitFor(
            "native preview for $fileName",
            2.minutes,
            200.milliseconds,
            { result: TypstPreviewResultRef? ->
                "source=${result?.getSourceFile()?.getPath()}, running=${result?.isRunning()}, " +
                    "url=${result?.getPreviewUrl()}, failure=${result?.getFailureMessage()}"
            },
            {
                val file = withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
                    fileEditorManager.getCurrentFile()
                }
                file?.let(previewService::statusFor)
            },
            { result: TypstPreviewResultRef? ->
                result?.getPreviewUrl() != null || result?.getFailureMessage() != null
            },
        )
        val completed = requireNotNull(result) { "$fileName did not publish a preview result" }
        check(completed.getFailureMessage() == null) {
            "$fileName preview failed: ${completed.getFailureMessage()}"
        }
        return requireNotNull(completed.getPreviewUrl()) { "$fileName did not produce a native preview URL" }
    }

    private fun Driver.waitForPreviewPage(expectedUrl: String) {
        waitFor(
            "JCEF navigation to $expectedUrl",
            1.minutes,
            200.milliseconds,
            { url: String -> "url=$url" },
            { ideFrame().jcef().getUrl() },
            { url -> url == expectedUrl },
        )
        waitFor(
            "rendered preview page for $expectedUrl",
            1.minutes,
            500.milliseconds,
            { hasPage: Boolean -> "hasPage=$hasPage" },
            {
                runCatching {
                    ideFrame().jcef().callJs(
                        "Boolean(document.querySelector('.page'))",
                        10_000,
                    ).toBoolean()
                }.getOrDefault(false)
            },
            { hasPage -> hasPage },
        )
    }

    private fun Driver.waitForPreviewSelection(previousToken: Long = 0): Long {
        val token = waitFor(
            "preview selection feedback after token $previousToken",
            1.minutes,
            100.milliseconds,
            { value: Long -> "selectionToken=$value" },
            {
                runCatching {
                    ideFrame().jcef().callJs(
                        "Number(window.__typstPreviewLastSelectionToken || 0)",
                        10_000,
                    ).toLong()
                }.getOrDefault(0)
            },
            { value -> value != 0L && value != previousToken },
        )
        waitFor(
            "selected source line highlight",
            1.minutes,
            100.milliseconds,
            { hasHighlight: Boolean -> "hasHighlight=$hasHighlight" },
            {
                runCatching {
                    ideFrame().jcef().callJs(
                        "Boolean(document.querySelector('.selection-line'))",
                        10_000,
                    ).toBoolean()
                }.getOrDefault(false)
            },
            { hasHighlight -> hasHighlight },
        )
        return token
    }

    private fun Driver.waitForPreviewFailure(project: Project, source: VirtualFile) {
        val previewService = service(TypstPreviewServiceRef::class, project)
        waitFor(
            "invalid source preview failure",
            1.minutes,
            200.milliseconds,
            { result: TypstPreviewResultRef? ->
                "running=${result?.isRunning()}, failure=${result?.getFailureMessage()}"
            },
            { previewService.statusFor(source) },
            { result -> !result?.getFailureMessage().isNullOrBlank() },
        )
    }

    private fun Driver.clickPreviewPosition(
        project: Project,
        page: Int,
        x: Double,
        y: Double,
        expectedRelativePath: String,
    ) {
        val fileEditorManager = service(FileEditorManager::class, project)
        val browser = ideFrame().jcef()
        browser.callJs(
            "(fetch('pages/' + document.querySelector('.page[data-page=\"$page\"]').dataset.key + '.svg')" +
                ".then(response => response.text())" +
                ".then(svg => document.querySelector('.page[data-page=\"$page\"]').innerHTML = svg)," +
                " 'requested')",
            10_000,
        )
        waitFor(
            "SVG on preview page $page",
            1.minutes,
            200.milliseconds,
            { hasSvg: Boolean -> "hasSvg=$hasSvg" },
            {
                browser.callJs(
                    "Boolean(document.querySelector('.page[data-page=\"$page\"] svg'))",
                    10_000,
                ).toBoolean()
            },
            { hasSvg -> hasSvg },
        )
        waitFor(
            "preview position bridge",
            10_000.milliseconds,
            100.milliseconds,
            { installed: Boolean -> "installed=$installed" },
            {
                browser.callJs(
                    "Boolean(window.__typstPreviewPositionBridgeInstalled)",
                    10_000,
                ).toBoolean()
            },
            { installed -> installed },
        )
        browser.callJs(
            "(document.addEventListener('click', event => {" +
                " window.__testPreviewClickSeen = true;" +
                " window.__testPreviewClickPrevented = event.defaultPrevented;" +
                "}, {once:true}), 'installed')",
            10_000,
        )
        val clickResult = browser.callJs(
            """
            (() => {
              const section = document.querySelector('.page[data-page="$page"]');
              const svg = section?.querySelector('svg');
              if (!section || !svg) return 'missing';
              const identity = svg.getCTM();
              if (!identity) return 'missing-matrix';
              identity.a = 1;
              identity.b = 0;
              identity.c = 0;
              identity.d = 1;
              identity.e = 0;
              identity.f = 0;
              Object.defineProperty(svg, 'getScreenCTM', {
                configurable: true,
                value: () => identity,
              });
              setTimeout(() => section.dispatchEvent(new MouseEvent('click', {
                bubbles: true,
                cancelable: true,
                clientX: $x,
                clientY: $y,
              })), 1000);
              return 'scheduled';
            })()
            """.trimIndent(),
            10_000,
        )
        check(clickResult == "scheduled") { "Preview coordinate could not be clicked: $clickResult" }
        Thread.sleep(2_000)
        val hasSeenClick = browser.callJs("Boolean(window.__testPreviewClickSeen)", 10_000).toBoolean()
        val hasHandledClick = browser.callJs("Boolean(window.__testPreviewClickPrevented)", 10_000).toBoolean()
        check(hasSeenClick && hasHandledClick) {
            "Preview DOM click was not handled: seen=$hasSeenClick, prevented=$hasHandledClick"
        }
        waitFor(
            "preview click navigation to $expectedRelativePath",
            1.minutes,
            200.milliseconds,
            { path: String -> "currentFile=$path" },
            {
                withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
                    fileEditorManager.getCurrentFile().getPath()
                }
            },
            { path -> path.endsWith(expectedRelativePath) },
        )
    }
}

@Remote("com.livteam.typninja.settings.TypstSettingsService", plugin = "com.livteam.typninja")
private interface TypstSettingsServiceRef {
    fun getState(): TypstSettingsStateRef
}

@Remote("com.livteam.typninja.settings.TypstSettingsService\$Settings", plugin = "com.livteam.typninja")
private interface TypstSettingsStateRef {
    fun setPackagePath(value: String)
    fun setAutoDownloadPackages(value: Boolean)
    fun setUseNativeRenderer(value: Boolean)
}

@Remote("com.livteam.typninja.preview.TypstPreviewService", plugin = "com.livteam.typninja")
private interface TypstPreviewServiceRef {
    fun statusFor(source: VirtualFile): TypstPreviewResultRef?
    fun preview(source: VirtualFile, unsavedText: String?, documentVersion: Long)
}

@Remote("com.livteam.typninja.preview.TypstPreviewResult", plugin = "com.livteam.typninja")
private interface TypstPreviewResultRef {
    fun getSourceFile(): VirtualFile?
    fun getFailureMessage(): String?
    fun isRunning(): Boolean
    fun getPreviewUrl(): String?
}

@Remote("com.intellij.openapi.vfs.LocalFileSystem")
private interface LocalFileSystemRef {
    fun getInstance(): LocalFileSystemRef
    fun refreshAndFindFileByPath(path: String): VirtualFile?
}
