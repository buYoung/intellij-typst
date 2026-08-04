package com.livteam.typninja.runtime

import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.JarURLConnection
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.io.path.inputStream

internal data class RuntimeManifest(
    val protocolVersion: Int,
    val assets: List<RuntimeAsset>,
)

internal data class RuntimeAsset(
    val platform: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

internal object TypstRuntimePlatform {
    fun current(
        osName: String = System.getProperty("os.name"),
        architecture: String = System.getProperty("os.arch"),
    ): String? {
        val arch = when (architecture.lowercase()) {
            "aarch64", "arm64" -> "aarch64"
            "amd64", "x86_64" -> "x86_64"
            else -> return null
        }
        return when {
            osName.startsWith("Mac", ignoreCase = true) -> "$arch-apple-darwin"
            osName.startsWith("Windows", ignoreCase = true) && arch == "x86_64" -> "x86_64-pc-windows-msvc"
            osName.startsWith("Linux", ignoreCase = true) && arch == "x86_64" -> "x86_64-unknown-linux-gnu"
            else -> null
        }
    }
}

internal class TypstRuntimeInstaller {
    private val logger = Logger.getInstance(TypstRuntimeInstaller::class.java)

    suspend fun resolve(autoDownload: Boolean, onStatus: (TypstRuntimeStatus) -> Unit): Path? =
        withContext(Dispatchers.IO) {
            configuredRuntime()?.let {
                onStatus(TypstRuntimeStatus.READY)
                return@withContext it
            }
            val platform = TypstRuntimePlatform.current()
            if (platform == null) {
                onStatus(TypstRuntimeStatus.INCOMPATIBLE)
                return@withContext null
            }
            val manifest = loadManifest()
            if (manifest.protocolVersion != PROTOCOL_VERSION) {
                onStatus(TypstRuntimeStatus.INCOMPATIBLE)
                return@withContext null
            }
            val asset = manifest.assets.firstOrNull { it.platform == platform }
            if (asset == null || !isUsableAsset(asset)) {
                onStatus(TypstRuntimeStatus.UNINSTALLED)
                return@withContext null
            }
            val destination = cachePath(platform)
            if (Files.isRegularFile(destination) && verify(destination, asset)) {
                makeExecutable(destination)
                onStatus(TypstRuntimeStatus.READY)
                return@withContext destination
            }
            if (!autoDownload) {
                onStatus(TypstRuntimeStatus.UNINSTALLED)
                return@withContext null
            }
            onStatus(TypstRuntimeStatus.DOWNLOADING)
            runCatching { download(asset, destination) }
                .onFailure { logger.warn("Failed to install Typst runtime", it) }
                .getOrNull()
                ?.also { onStatus(TypstRuntimeStatus.READY) }
                ?: run {
                    onStatus(TypstRuntimeStatus.FAILED)
                    null
                }
        }

    private fun configuredRuntime(): Path? {
        val configured = System.getProperty("typst.runtime.path")
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("TYPST_RUNTIME_PATH")?.takeIf(String::isNotBlank)
        return configured?.let(Path::of)?.takeIf(Files::isRegularFile)
    }

    private fun loadManifest(): RuntimeManifest {
        val stream = javaClass.classLoader.getResourceAsStream(MANIFEST_RESOURCE)
            ?: return RuntimeManifest(PROTOCOL_VERSION, emptyList())
        return stream.bufferedReader().use { Gson().fromJson(it, RuntimeManifest::class.java) }
    }

    private fun cachePath(platform: String): Path {
        val pluginVersion = packagedPluginVersion() ?: "development"
        val executableName = if (platform.contains("windows")) "typst-runtime.exe" else "typst-runtime"
        return Path.of(PathManager.getSystemPath(), "typst", "runtime", pluginVersion, platform, executableName)
    }

    private fun packagedPluginVersion(): String? = runCatching {
        val classResource = javaClass.getResource("${javaClass.simpleName}.class") ?: return@runCatching null
        val connection = classResource.openConnection() as? JarURLConnection ?: return@runCatching null
        connection.manifest.mainAttributes.getValue("Version")?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun download(asset: RuntimeAsset, destination: Path): Path {
        require(URI(asset.url).scheme.equals("https", ignoreCase = true)) { "Runtime URL must use HTTPS" }
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, "typst-runtime-", ".download")
        try {
            val request = HttpRequest.newBuilder(URI(asset.url)).GET().build()
            val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(request, HttpResponse.BodyHandlers.ofFile(temporary))
            require(response.statusCode() in 200..299) { "Runtime download returned HTTP ${response.statusCode()}" }
            require(verify(temporary, asset)) { "Runtime size or SHA-256 does not match the manifest" }
            makeExecutable(temporary)
            runCatching {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            makeExecutable(destination)
            return destination
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun isUsableAsset(asset: RuntimeAsset): Boolean =
        asset.size > 0 && asset.sha256.matches(Regex("[0-9a-fA-F]{64}"))

    private fun verify(path: Path, asset: RuntimeAsset): Boolean {
        if (Files.size(path) != asset.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(asset.sha256, ignoreCase = true)
    }

    private fun makeExecutable(path: Path) {
        runCatching {
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
            Files.setPosixFilePermissions(path, permissions)
        }
    }

    private companion object {
        const val MANIFEST_RESOURCE = "typst-runtime/manifest.json"
        const val PROTOCOL_VERSION = 1
    }
}
