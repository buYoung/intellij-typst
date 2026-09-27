package com.livteam.typninja.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Serves already-rendered WASM pages; compilation never depends on the browser. */
internal class TypstPreviewServer : Disposable {
    private val isDisposed = AtomicBoolean()
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Typst WASM preview", 2)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val gson = Gson()
    private val shell = javaClass.classLoader.getResourceAsStream("typst-wasm/preview.html")!!
        .bufferedReader().use { it.readText() }.replace("__NONCE__", token)
    private data class Snapshot(val json: ByteArray, val resources: Map<String, ByteArray>)
    @Volatile private var snapshot = Snapshot("{\"generation\":0,\"pages\":[]}".toByteArray(), emptyMap())
    val url: String get() = "http://127.0.0.1:${server.address.port}/$token/"

    init {
        server.executor = executor
        server.createContext("/") { exchange -> exchange.use { serve(it) } }
        server.start()
    }

    fun update(generation: Long, output: JsonObject) {
        val resources = HashMap<String, ByteArray>()
        val svgPages = output.getAsJsonArray("svgPages")
        val regions = output.getAsJsonArray("regions")
        val pages = output.getAsJsonArray("pages").mapIndexed { index, item ->
            val page = item.asJsonObject
            val svg = svgPages[index].asString.toByteArray(Charsets.UTF_8)
            val region = regions[index].toString().toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(svg)
            val key = digest.digest(region).joinToString("") { "%02x".format(it) }
            resources["$key.svg"] = svg
            resources["$key.json"] = region
            mapOf("number" to page["number"].asInt, "width" to page["width"].asDouble, "height" to page["height"].asDouble, "key" to key)
        }
        snapshot = Snapshot(gson.toJson(mapOf("generation" to generation, "pages" to pages)).toByteArray(Charsets.UTF_8), resources)
    }

    private fun serve(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") { exchange.sendResponseHeaders(405, -1); return }
        val prefix = "/$token/"
        val path = exchange.requestURI.path
        val current = snapshot
        val body: ByteArray
        val contentType: String
        when {
            path == prefix -> { body = shell.toByteArray(Charsets.UTF_8); contentType = "text/html; charset=utf-8" }
            path == "${prefix}snapshot.json" -> { body = current.json; contentType = "application/json" }
            path.startsWith("${prefix}pages/") -> {
                val name = path.removePrefix("${prefix}pages/")
                body = current.resources[name] ?: run { exchange.sendResponseHeaders(404, -1); return }
                contentType = if (name.endsWith(".svg")) "image/svg+xml" else "application/json"
            }
            else -> { exchange.sendResponseHeaders(404, -1); return }
        }
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("Content-Security-Policy", "default-src 'none'; script-src 'nonce-$token'; style-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src data:; frame-ancestors 'none'")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body)
    }

    override fun dispose() {
        if (isDisposed.compareAndSet(false, true)) {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
