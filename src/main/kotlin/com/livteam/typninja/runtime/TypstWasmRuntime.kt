package com.livteam.typninja.runtime

import com.dylibso.chicory.compiler.MachineFactoryCompiler
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.lang.ref.SoftReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.function.Function

/** A single-owner WASM instance. Discard it after a trap or thread interruption. */
internal class TypstWasmRuntime(path: Path, val version: String) {
    private val gson = Gson()
    private val fontFiles = ArrayList<Path>()
    private val instance: Instance
    var isUsable = true
        private set

    init {
        val compiled = compiledModule(path)
        val fontReader = HostFunction("typst_host", "read_font_bytes",
            FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32))) { caller, arguments ->
            checkCancellation()
            val file = fontFiles.getOrNull(arguments[0].toInt())
            val size = file?.takeIf(Files::isRegularFile)?.let(Files::size) ?: -1
            val capacity = arguments[2].toInt()
            val result = if (size !in 1..MAX_FONT_BYTES) -1 else if (capacity == 0) size.toInt() else {
                if (capacity != size.toInt()) -1 else {
                    val bytes = Files.readAllBytes(file!!)
                    if (bytes.size != capacity) -1 else {
                        caller.memory().write(arguments[1].toInt(), bytes)
                        bytes.size
                    }
                }
            }
            longArrayOf(result.toLong())
        }
        instance = Instance.builder(compiled.module).withMachineFactory(compiled.factory)
            .withImportValues(ImportValues.builder().addFunction(fontReader).build()).build()
        val identity = request(mapOf("method" to "version")).asJsonObject
        check(identity["apiVersion"].asInt == 1 && identity["typstVersion"].asString == version) {
            "Typst WASM version or ABI does not match the selected engine"
        }
    }

    fun request(command: Map<String, Any?>, bytes: ByteArray = byteArrayOf()): JsonElement {
        check(isUsable) { "Interrupted Typst WASM instances cannot be reused" }
        checkCancellation()
        try {
            val input = allocate(gson.toJson(command).toByteArray(Charsets.UTF_8))
            try {
                val data = allocate(bytes)
                try {
                    val packed = try {
                        instance.export("request").apply(input.pointer, input.length, data.pointer, data.length).single()
                    } catch (failure: Throwable) {
                        isUsable = false
                        throw failure
                    }
                    val response = JsonParser.parseString(readAndRelease(packed).toString(Charsets.UTF_8)).asJsonObject
                    response["error"]?.let { throw IllegalArgumentException(it.asString) }
                    return response["ok"]
                } finally { if (isUsable) release(data) }
            } finally { if (isUsable) release(input) }
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (failure: Throwable) {
            isUsable = false
            throw failure
        }
    }

    fun takeOutput(index: Int): ByteArray = try {
        readAndRelease(instance.export("take_output").apply(index.toLong()).single())
    } catch (failure: Throwable) {
        isUsable = false
        throw failure
    }

    fun registerFonts(paths: List<Path>) {
        for (path in paths) {
            checkCancellation()
            if (!Files.isRegularFile(path) || Files.size(path) !in 1..MAX_FONT_BYTES) continue
            val id = fontFiles.size
            fontFiles.add(path)
            try {
                request(mapOf("method" to "registerFont", "id" to id), Files.readAllBytes(path))
            } catch (_: IllegalArgumentException) {
                // Font directories may contain unsupported or malformed font files.
            }
        }
    }

    private data class Buffer(val pointer: Long, val length: Long)
    private fun allocate(bytes: ByteArray): Buffer {
        if (bytes.isEmpty()) return Buffer(0, 0)
        val pointer = instance.export("alloc").apply(bytes.size.toLong()).single()
        check(pointer != 0L) { "Typst WASM allocation failed" }
        val buffer = Buffer(pointer, bytes.size.toLong())
        try { instance.memory().write(pointer.toInt(), bytes) } catch (failure: Throwable) {
            release(buffer)
            throw failure
        }
        return buffer
    }
    private fun readAndRelease(packed: Long): ByteArray {
        if (packed == 0L) return byteArrayOf()
        val buffer = Buffer(packed ushr 32, packed and 0xffffffffL)
        require(buffer.length <= Int.MAX_VALUE) { "Typst WASM output exceeds the JVM buffer limit" }
        return try { instance.memory().readBytes(buffer.pointer.toInt(), buffer.length.toInt()) } finally { release(buffer) }
    }
    private fun release(buffer: Buffer) {
        if (buffer.pointer != 0L && !Thread.currentThread().isInterrupted) {
            instance.export("dealloc").apply(buffer.pointer, buffer.length)
        }
    }

    companion object {
        private const val MAX_FONT_BYTES = 256L * 1024 * 1024
        private data class CompiledModule(val path: Path, val module: WasmModule, val factory: Function<Instance, Machine>)
        private var cachedModule = SoftReference<CompiledModule>(null)

        @Synchronized
        private fun compiledModule(path: Path): CompiledModule {
            cachedModule.get()?.takeIf { it.path == path }?.let { return it }
            checkCancellation()
            val module = Parser.parse(path)
            val compiled = CompiledModule(path, module, MachineFactoryCompiler.compile(module))
            cachedModule = SoftReference(compiled)
            return compiled
        }

        fun checkCancellation() {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Typst WASM compilation cancelled")
        }
    }
}
