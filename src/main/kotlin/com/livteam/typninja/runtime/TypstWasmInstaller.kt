package com.livteam.typninja.runtime

import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal data class TypstWasmAsset(
    val version: String, val url: String, val sizeBytes: Long, val sha256: String,
    val rawWasmSizeBytes: Long, val rawWasmSha256: String,
)
internal data class TypstWasmManifest(val releaseVersion: String, val engines: List<TypstWasmAsset>)

internal object TypstWasmInstaller {
    private val installMutex = Mutex()
    val manifest: TypstWasmManifest by lazy {
        val stream = javaClass.classLoader.getResourceAsStream("typst-wasm/manifest.json")
            ?: error("Typst WASM manifest is missing; build the WASM distribution before packaging the plugin")
        stream.bufferedReader().use { Gson().fromJson(it, TypstWasmManifest::class.java) }
    }
    val versions: List<String> get() = manifest.engines.map { it.version }
    val defaultVersion: String get() = versions.last()

    suspend fun resolve(version: String, shouldDownload: Boolean, onStatus: (TypstRuntimeStatus) -> Unit = {}): Path =
        installMutex.withLock {
            runInterruptible(Dispatchers.IO) {
                val asset = manifest.engines.singleOrNull { it.version == version }
                    ?: error("Unsupported Typst WASM engine: $version")
                val destination = Path.of(PathManager.getSystemPath(), "typst", "wasm", manifest.releaseVersion, version, "typst_wasm_raw.wasm")
                if (verify(destination, asset.rawWasmSizeBytes, asset.rawWasmSha256)) {
                    onStatus(TypstRuntimeStatus.READY)
                    return@runInterruptible destination
                }
                Files.createDirectories(destination.parent)
                val temporary = Files.createTempFile(destination.parent, "engine-", ".wasm")
                try {
                    val bundled = javaClass.classLoader.getResourceAsStream("typst-wasm/$version.wasm")
                    if (bundled != null) {
                        bundled.use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
                    } else {
                        check(shouldDownload) { "Typst WASM $version is not installed. Enable engine downloads in Typst settings." }
                        onStatus(TypstRuntimeStatus.DOWNLOADING)
                        val archive = Files.createTempFile(destination.parent, "engine-", ".zip")
                        try {
                            TypstDownloads.download(asset.url, archive, asset.sizeBytes)
                            check(verify(archive, asset.sizeBytes, asset.sha256)) { "Typst WASM archive checksum mismatch" }
                            ZipInputStream(Files.newInputStream(archive)).use { zip ->
                                var found = false
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    if (entry.name == "typst_wasm_raw.wasm") {
                                        Files.newOutputStream(temporary).use { TypstDownloads.copy(zip, it, asset.rawWasmSizeBytes) }
                                        found = true
                                        break
                                    }
                                }
                                check(found) { "Typst WASM archive does not contain the JVM module" }
                            }
                        } finally { Files.deleteIfExists(archive) }
                    }
                    check(verify(temporary, asset.rawWasmSizeBytes, asset.rawWasmSha256)) { "Typst WASM module checksum mismatch" }
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                    onStatus(TypstRuntimeStatus.READY)
                    destination
                } finally { Files.deleteIfExists(temporary) }
            }
        }

    private fun verify(path: Path, sizeBytes: Long, sha256: String): Boolean {
        if (sizeBytes <= 0 || !sha256.matches(Regex("[0-9a-f]{64}")) || !Files.isRegularFile(path) || Files.size(path) != sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                TypstWasmRuntime.checkCancellation()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }
}
