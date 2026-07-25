package com.livteam.typninja.execution

import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Finds a Typst executable without requiring project settings. An explicit setting always wins. */
internal object TypstExecutableResolver {
    fun resolve(project: Project, configuredPath: String): String? {
        configuredPath.trim().takeIf(String::isNotEmpty)?.let { configured ->
            val configuredExecutable = expandHome(configured)
            val resolvedExecutable = if (configuredExecutable.isAbsolute) {
                configuredExecutable
            } else {
                project.basePath?.let { Path.of(it).resolve(configuredExecutable) }
                    ?: configuredExecutable.toAbsolutePath()
            }
            return resolvedExecutable.normalize().toString()
        }

        val executableName = if (isWindows()) "typst.exe" else "typst"
        val candidates = LinkedHashSet<Path>()
        addEnvironmentCandidate(candidates, "TYPST_EXECUTABLE")
        addEnvironmentCandidate(candidates, "TYPST")
        addPathCandidates(candidates, executableName)

        val userHome = System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let { Path.of(it) }
        if (userHome != null) {
            candidates.add(userHome.resolve(".cargo").resolve("bin").resolve(executableName))
            candidates.add(userHome.resolve(".local").resolve("bin").resolve(executableName))
            if (isWindows()) {
                candidates.add(userHome.resolve("scoop").resolve("shims").resolve(executableName))
            }
        }

        if (isWindows()) {
            System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let { Path.of(it) }?.let { localAppData ->
                candidates.add(localAppData.resolve("Microsoft").resolve("WinGet").resolve("Links").resolve(executableName))
            }
        } else {
            candidates.add(Path.of("/opt/homebrew/bin/typst"))
            candidates.add(Path.of("/usr/local/bin/typst"))
            candidates.add(Path.of("/usr/bin/typst"))
        }

        return candidates.asSequence()
            .map { it.toAbsolutePath().normalize() }
            .firstOrNull(::isRunnableFile)
            ?.toString()
    }

    private fun addEnvironmentCandidate(candidates: MutableSet<Path>, variableName: String) {
        System.getenv(variableName)?.takeIf(String::isNotBlank)?.let { value ->
            runCatching { candidates.add(expandHome(value.trim())) }
        }
    }

    private fun addPathCandidates(candidates: MutableSet<Path>, executableName: String) {
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { directory -> runCatching { candidates.add(Path.of(directory).resolve(executableName)) } }
    }

    private fun expandHome(value: String): Path {
        if (value == "~") return Path.of(System.getProperty("user.home"))
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home")).resolve(value.substring(2))
        }
        return Path.of(value)
    }

    private fun isRunnableFile(path: Path): Boolean =
        Files.isRegularFile(path) && (isWindows() || Files.isExecutable(path))

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
