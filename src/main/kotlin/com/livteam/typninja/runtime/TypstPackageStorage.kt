package com.livteam.typninja.runtime

import com.intellij.openapi.project.Project
import com.livteam.typninja.language.references.TypstPackageResolver
import com.livteam.typninja.settings.TypstSettingsService
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

internal object TypstPackageStorage {
    private const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024

    fun roots(project: Project): List<Path> {
        val settings = TypstSettingsService.getInstance(project)
        val root = settings.workspaceRoot()
        val paths = settings.packageRoots().map { resolvePath(project, root, it) }.toMutableList()
        if (settings.state.useDefaultPackageRoots) {
            System.getenv("TYPST_PACKAGE_PATH")?.takeIf(String::isNotBlank)?.let { paths.add(Path.of(it)) }
            System.getenv("TYPST_PACKAGE_CACHE_PATH")?.takeIf(String::isNotBlank)?.let { paths.add(Path.of(it)) }
            val home = Path.of(System.getProperty("user.home"))
            paths.add(home.resolve("Library/Application Support/typst/packages"))
            paths.add(home.resolve("Library/Caches/typst/packages"))
            paths.add(Path.of(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString()).resolve("typst/packages"))
            paths.add(Path.of(System.getenv("XDG_CACHE_HOME") ?: home.resolve(".cache").toString()).resolve("typst/packages"))
            System.getenv("APPDATA")?.let { paths.add(Path.of(it, "typst", "packages")) }
            System.getenv("LOCALAPPDATA")?.let { paths.add(Path.of(it, "typst", "packages")) }
        }
        return paths.map { it.toAbsolutePath().normalize() }.distinct()
    }

    fun find(project: Project, specification: String): Path? {
        val spec = TypstPackageResolver.parse(specification) ?: return null
        if (spec.version == "." || spec.version == "..") return null
        return roots(project).map { it.resolve(spec.namespace).resolve(spec.name).resolve(spec.version) }
            .firstOrNull { Files.isRegularFile(it.resolve("typst.toml")) }
    }

    @Synchronized
    fun ensure(project: Project, specification: String): Path? {
        TypstWasmRuntime.checkCancellation()
        find(project, specification)?.let { return it }
        val settings = TypstSettingsService.getInstance(project)
        val spec = TypstPackageResolver.parse(specification) ?: return null
        if (spec.version == "." || spec.version == "..") return null
        if (spec.namespace != "preview" || !settings.state.autoDownloadPackages) return null
        val configured = settings.state.packageCachePath?.takeIf(String::isNotBlank)
        val cache = if (configured != null) resolvePath(project, settings.workspaceRoot(), configured) else defaultCache()
        // When default roots are disabled, downloads need an explicitly configured cache.
        if (configured == null && !settings.state.useDefaultPackageRoots) return null
        val destination = cache.resolve(spec.namespace).resolve(spec.name).resolve(spec.version)
        Files.createDirectories(destination.parent)
        val archive = Files.createTempFile(destination.parent, "package-", ".tar.gz")
        val staging = Files.createTempDirectory(destination.parent, "package-")
        try {
            TypstDownloads.download("https://packages.typst.org/preview/${spec.name}-${spec.version}.tar.gz", archive, MAX_PACKAGE_BYTES)
            var expandedBytes = 0L
            TarArchiveInputStream(GZIPInputStream(Files.newInputStream(archive))).use { tar ->
                while (true) {
                    TypstWasmRuntime.checkCancellation()
                    val entry = tar.nextEntry ?: break
                    require(entry.isDirectory || entry.isFile) { "Typst package contains a non-regular entry" }
                    require(!entry.name.contains('\\') && !entry.name.contains(':')) { "Invalid Typst package path" }
                    val target = staging.resolve(entry.name).normalize()
                    require(target.startsWith(staging)) { "Typst package entry escapes its directory" }
                    if (entry.isDirectory) Files.createDirectories(target) else {
                        Files.createDirectories(target.parent)
                        Files.newOutputStream(target).use { expandedBytes += TypstDownloads.copy(tar, it, MAX_EXPANDED_BYTES - expandedBytes) }
                    }
                }
            }
            check(Files.isRegularFile(staging.resolve("typst.toml"))) { "Typst package manifest is missing" }
            if (Files.exists(destination)) {
                check(Files.isRegularFile(destination.resolve("typst.toml"))) { "An incomplete Typst package already exists: $destination" }
            } else Files.move(staging, destination)
            return destination
        } finally {
            Files.deleteIfExists(archive)
            if (Files.exists(staging)) Files.walk(staging).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun defaultCache(): Path {
        System.getenv("TYPST_PACKAGE_CACHE_PATH")?.takeIf(String::isNotBlank)?.let { return Path.of(it).toAbsolutePath().normalize() }
        val home = Path.of(System.getProperty("user.home"))
        return when {
            System.getProperty("os.name").startsWith("Mac") -> home.resolve("Library/Caches/typst/packages")
            System.getProperty("os.name").startsWith("Windows") -> Path.of(System.getenv("LOCALAPPDATA") ?: home.toString(), "typst", "packages")
            else -> Path.of(System.getenv("XDG_CACHE_HOME") ?: home.resolve(".cache").toString()).resolve("typst/packages")
        }
    }

    private fun resolvePath(project: Project, root: Path, value: String): Path {
        val path = Path.of(value.replace("${'$'}{workspaceFolder}", project.basePath.orEmpty()))
        return (if (path.isAbsolute) path else root.resolve(path)).normalize()
    }
}
