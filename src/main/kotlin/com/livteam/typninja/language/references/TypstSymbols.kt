package com.livteam.typninja.language.references

import java.nio.charset.StandardCharsets

/** Typst's complete built-in `sym` and `emoji` trees, loaded from bundled Codex 0.3 data. */
object TypstSymbols {

    data class CatalogEntry(val path: String, val glyph: String)

    private data class SymbolEntry(
        val fullPath: String,
        val glyph: String?,
        val isModule: Boolean,
    ) {
        val ownerPath: String get() = fullPath.substringBeforeLast('.')
        val name: String get() = fullPath.substringAfterLast('.')

        fun metadata(): TypstBuiltins.Metadata = TypstBuiltins.Metadata(
            name = name,
            kind = if (isModule) TypstBuiltins.Kind.MODULE else TypstBuiltins.Kind.VALUE,
            summary = glyph?.let { "Typst symbol $it" } ?: "Typst symbol group.",
            returnType = if (isModule) "module" else "symbol",
        )
    }

    private val entries: List<SymbolEntry> by lazy {
        parse("sym", "/typst/sym.txt") + parse("emoji", "/typst/emoji.txt")
    }

    private val entriesByOwner: Map<String, List<SymbolEntry>> by lazy {
        entries.groupBy(SymbolEntry::ownerPath).mapValues { (_, values) -> values.sortedBy(SymbolEntry::name) }
    }

    fun members(ownerPath: String): List<TypstBuiltins.Metadata> =
        entriesByOwner[ownerPath].orEmpty().map(SymbolEntry::metadata)

    fun metadata(ownerPath: String, memberName: String): TypstBuiltins.Metadata? =
        entriesByOwner[ownerPath]?.firstOrNull { it.name == memberName }?.metadata()

    fun allNames(): Set<String> = entries.asSequence().map(SymbolEntry::name).toSet()

    fun catalog(): List<CatalogEntry> = entries.asSequence()
        .filter { !it.isModule && !it.glyph.isNullOrEmpty() }
        .map { CatalogEntry(it.fullPath, it.glyph.orEmpty()) }
        .distinctBy(CatalogEntry::path)
        .sortedBy(CatalogEntry::path)
        .toList()

    fun anyMetadata(name: String): TypstBuiltins.Metadata? =
        entries.firstOrNull { it.name == name }?.metadata()

    private fun parse(rootName: String, resourcePath: String): List<SymbolEntry> {
        val stream = TypstSymbols::class.java.getResourceAsStream(resourcePath) ?: return emptyList()
        val moduleStack = ArrayDeque<String>()
        val parsed = ArrayList<SymbolEntry>()
        var lastSymbolPath: String? = null
        stream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { sourceLine ->
                val line = sourceLine.substringBefore("//").trim()
                when {
                    line.isEmpty() || line.startsWith("@deprecated:") -> Unit
                    line == "}" -> {
                        if (moduleStack.isNotEmpty()) moduleStack.removeLast()
                        lastSymbolPath = null
                    }
                    line.endsWith(" {") -> {
                        val moduleName = line.removeSuffix(" {").trim()
                        val fullPath = path(rootName, moduleStack, moduleName)
                        parsed.add(SymbolEntry(fullPath, null, true))
                        moduleStack.addLast(moduleName)
                        lastSymbolPath = null
                    }
                    line.startsWith('.') -> {
                        val separator = line.indexOf(' ')
                        if (separator <= 1) return@forEach
                        val modifiers = line.substring(0, separator)
                        val value = decode(line.substring(separator + 1).trim())
                        lastSymbolPath?.let { parsed.add(SymbolEntry(it + modifiers, value, false)) }
                    }
                    else -> {
                        val separator = line.indexOf(' ')
                        val symbolName = if (separator < 0) line else line.substring(0, separator)
                        val value = if (separator < 0) null else decode(line.substring(separator + 1).trim())
                        val fullPath = path(rootName, moduleStack, symbolName)
                        parsed.add(SymbolEntry(fullPath, value, false))
                        lastSymbolPath = fullPath
                    }
                }
            }
        }
        return parsed
    }

    private fun path(rootName: String, modules: Collection<String>, name: String): String =
        buildList {
            add(rootName)
            addAll(modules)
            add(name)
        }.joinToString(".")

    private fun decode(source: String): String {
        val output = StringBuilder()
        var offset = 0
        while (offset < source.length) {
            when {
                source.startsWith("\\u{", offset) -> {
                    val end = source.indexOf('}', offset + 3)
                    if (end < 0) break
                    source.substring(offset + 3, end).toIntOrNull(16)?.let(output::appendCodePoint)
                    offset = end + 1
                }
                source.startsWith("\\vs{", offset) -> {
                    val end = source.indexOf('}', offset + 4)
                    if (end < 0) break
                    val selector = when (val value = source.substring(offset + 4, end)) {
                        "text", "15" -> 0xFE0E
                        "emoji", "16" -> 0xFE0F
                        else -> value.toIntOrNull()?.takeIf { it in 1..14 }?.let { 0xFDFF + it }
                    }
                    if (selector != null) output.appendCodePoint(selector)
                    offset = end + 1
                }
                else -> output.append(source[offset++])
            }
        }
        return output.toString()
    }
}
