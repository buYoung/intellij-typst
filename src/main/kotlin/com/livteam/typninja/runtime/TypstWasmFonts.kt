package com.livteam.typninja.runtime

import java.nio.file.Files
import java.nio.file.Path

internal object TypstWasmFonts {
    fun find(configured: List<Path>, shouldUseSystemFonts: Boolean): List<Path> {
        val roots = configured.toMutableList()
        if (shouldUseSystemFonts) {
            val home = Path.of(System.getProperty("user.home"))
            when {
                System.getProperty("os.name").startsWith("Mac") -> roots.addAll(listOf(Path.of("/System/Library/Fonts"), Path.of("/Library/Fonts"), home.resolve("Library/Fonts")))
                System.getProperty("os.name").startsWith("Windows") -> {
                    roots.add(Path.of(System.getenv("WINDIR") ?: "C:\\Windows", "Fonts"))
                    System.getenv("LOCALAPPDATA")?.let { roots.add(Path.of(it, "Microsoft", "Windows", "Fonts")) }
                }
                else -> roots.addAll(listOf(Path.of("/usr/share/fonts"), Path.of("/usr/local/share/fonts"), home.resolve(".fonts"),
                    Path.of(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString()).resolve("fonts")))
            }
        }
        System.getenv("TYPST_FONT_PATHS")?.split(java.io.File.pathSeparator)?.filter(String::isNotBlank)?.map(Path::of)?.let(roots::addAll)
        val fonts = linkedSetOf<Path>()
        for (root in roots.distinct()) {
            TypstWasmRuntime.checkCancellation()
            if (!Files.exists(root)) continue
            Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.').lowercase() in setOf("ttf", "otf", "ttc", "otc") }
                .forEach { TypstWasmRuntime.checkCancellation(); fonts.add(it.toRealPath()) } }
        }
        return fonts.toList()
    }
}
