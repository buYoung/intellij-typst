package com.livteam.typninja.language.analysis

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.application.ApplicationManager
import com.livteam.typninja.language.references.TypstPackageSpec
import com.livteam.typninja.settings.TypstSettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class TypstPackageCatalog(
    val entrypoints: Map<String, VirtualFile> = emptyMap(),
    val packageRoots: List<VirtualFile> = emptyList(),
    val generation: Long = 0,
) {
    fun entrypoint(specification: String): VirtualFile? = entrypoints[specification]
    fun specifications(): List<String> = entrypoints.keys.sorted()
}

/** Owns the asynchronous, local-only package catalog for one IntelliJ project. */
@Service(Service.Level.PROJECT)
class TypstProjectModelService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : ModificationTracker, Disposable {
    private val logger = Logger.getInstance(TypstProjectModelService::class.java)
    private val refreshRunning = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)
    private val catalogInitialized = AtomicBoolean(false)
    private val catalogGeneration = AtomicLong()
    private val listeners = java.util.concurrent.ConcurrentHashMap.newKeySet<(TypstPackageCatalog) -> Unit>()

    @Volatile
    private var catalog = TypstPackageCatalog()

    @Volatile
    private var refreshJob: Job? = null

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::changesPackageRoot)) requestRefresh()
                }
            },
        )
    }

    fun packageCatalog(): TypstPackageCatalog {
        scheduleRefreshIfNeeded()
        return catalog
    }

    fun addListener(listener: (TypstPackageCatalog) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    override fun getModificationCount(): Long = catalog.generation

    fun requestRefresh() {
        refreshRequested.set(true)
        if (!refreshRunning.compareAndSet(false, true)) return
        refreshJob = coroutineScope.launch {
            try {
                while (refreshRequested.getAndSet(false)) {
                    val refreshed = withContext(Dispatchers.IO) { scanPackageCatalog() }
                    val previousCatalog = catalog
                    val catalogChanged = previousCatalog.entrypoints != refreshed.entrypoints ||
                        previousCatalog.packageRoots.map(VirtualFile::getUrl) != refreshed.packageRoots.map(VirtualFile::getUrl)
                    if (!catalogInitialized.getAndSet(true) || catalogChanged) {
                        val publishedCatalog = refreshed.copy(generation = catalogGeneration.incrementAndGet())
                        catalog = publishedCatalog
                        DaemonCodeAnalyzer.getInstance(project).restart()
                        ApplicationManager.getApplication().invokeLater {
                            listeners.forEach { listener -> listener(publishedCatalog) }
                        }
                    }
                }
            } catch (exception: java.util.concurrent.CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn("Unable to refresh local Typst package catalog", exception)
            } finally {
                refreshRunning.set(false)
                if (refreshRequested.get()) requestRefresh()
            }
        }
    }

    private fun changesPackageRoot(event: VFileEvent): Boolean {
        val eventPath = runCatching { Paths.get(event.path).normalize() }.getOrNull() ?: return false
        return packageRootPaths().any { root -> eventPath.startsWith(root.normalize()) || root.normalize().startsWith(eventPath) }
    }

    override fun dispose() {
        refreshJob?.cancel()
        listeners.clear()
    }

    private fun scheduleRefreshIfNeeded() {
        if (!catalogInitialized.get()) requestRefresh()
    }

    private fun scanPackageCatalog(): TypstPackageCatalog {
        val entrypoints = LinkedHashMap<String, VirtualFile>()
        val roots = ArrayList<VirtualFile>()
        for (configuredRoot in packageRootPaths()) {
            ProgressManager.checkCanceled()
            val root = configuredRoot.normalize()
            if (!Files.isDirectory(root)) continue
            val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: continue
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(realRoot)?.let(roots::add)
            Files.newDirectoryStream(realRoot).use { namespaces ->
                for (namespace in namespaces) {
                    ProgressManager.checkCanceled()
                    if (!Files.isDirectory(namespace)) continue
                    Files.newDirectoryStream(namespace).use { packages ->
                        for (packageDirectory in packages) {
                            ProgressManager.checkCanceled()
                            if (!Files.isDirectory(packageDirectory)) continue
                            Files.newDirectoryStream(packageDirectory).use { versions ->
                                for (versionDirectory in versions) {
                                    ProgressManager.checkCanceled()
                                    if (!Files.isDirectory(versionDirectory)) continue
                                    val realPackage = runCatching { versionDirectory.toRealPath() }.getOrNull() ?: continue
                                    if (!realPackage.startsWith(realRoot)) continue
                                    val manifest = realPackage.resolve("typst.toml")
                                    val entrypoint = readEntrypoint(manifest) ?: continue
                                    val target = realPackage.resolve(entrypoint).normalize()
                                    val realTarget = runCatching { target.toRealPath() }.getOrNull() ?: continue
                                    if (!realTarget.startsWith(realPackage) || !Files.isRegularFile(realTarget)) continue
                                    val specification = TypstPackageSpec(
                                        namespace.fileName.toString(),
                                        packageDirectory.fileName.toString(),
                                        versionDirectory.fileName.toString(),
                                    ).toString()
                                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(realTarget)?.let {
                                        entrypoints.putIfAbsent(specification, it)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return TypstPackageCatalog(entrypoints, roots.distinctBy { it.url })
    }

    private fun readEntrypoint(manifest: Path): String? {
        if (!Files.isRegularFile(manifest)) return null
        val text = runCatching { Files.readString(manifest, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        return ENTRYPOINT_PATTERN.find(text)?.groupValues?.get(1)
    }

    private fun packageRootPaths(): List<Path> {
        val settings = project.service<TypstSettingsService>()
        val roots = LinkedHashSet<Path>()
        settings.packageRoots().forEach { configuredRoot ->
            runCatching { Paths.get(configuredRoot) }.getOrNull()?.let(roots::add)
        }
        if (!settings.state.useDefaultPackageRoots) return roots.toList()
        System.getenv("TYPST_PACKAGE_PATH")?.takeIf { it.isNotBlank() }?.let { configuredRoot ->
            runCatching { Paths.get(configuredRoot) }.getOrNull()?.let(roots::add)
        }
        val home = Paths.get(System.getProperty("user.home"))
        roots.add(home.resolve("Library/Application Support/typst/packages"))
        roots.add(home.resolve("Library/Caches/typst/packages"))
        roots.add(Paths.get(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString()).resolve("typst/packages"))
        roots.add(Paths.get(System.getenv("XDG_CACHE_HOME") ?: home.resolve(".cache").toString()).resolve("typst/packages"))
        return roots.toList()
    }

    companion object {
        private val ENTRYPOINT_PATTERN = Regex("(?m)^\\s*entrypoint\\s*=\\s*\"([^\"]+)\"")

        fun getInstance(project: Project): TypstProjectModelService = project.service()
    }
}
