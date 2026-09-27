package com.livteam.typninja.runtime

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal object TypstDownloads {
    fun download(url: String, destination: Path, maxBytes: Long) {
        require(URI(url).scheme == "https") { "Typst downloads require HTTPS" }
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build().use { client ->
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(120)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                check(response.statusCode() == 200) { "Typst download returned HTTP ${response.statusCode()}" }
                Files.newOutputStream(destination).use { copy(input, it, maxBytes) }
            }
        }
    }

    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            TypstWasmRuntime.checkCancellation()
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            check(total <= maxBytes) { "Typst download or archive exceeds its size limit" }
            output.write(buffer, 0, count)
        }
    }
}
