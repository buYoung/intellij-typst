package com.livteam.typninja.runtime

import com.livteam.typninja.settings.TypstSettingsService

internal data class TypstWasmOptions(val values: Map<String, Any>, val inputs: Map<String, String>) {
    companion object {
        fun from(settings: TypstSettingsService, format: String, isPreview: Boolean): TypstWasmOptions {
            val values = linkedMapOf<String, Any>("format" to format, "ppi" to settings.state.previewPpi,
                "shouldRetainSourceMap" to isPreview)
            val inputs = linkedMapOf<String, String>()
            val arguments = settings.extraArguments() + if (isPreview) settings.previewArguments() else emptyList()
            var index = 0
            while (index < arguments.size) {
                val argument = arguments[index++]
                val name = argument.substringBefore('=')
                fun value(): String = if ('=' in argument) argument.substringAfter('=') else {
                    require(index < arguments.size) { "Missing value for $name" }
                    arguments[index++]
                }
                when (name) {
                    "--input" -> {
                        val input = value()
                        require('=' in input) { "--input requires key=value" }
                        inputs[input.substringBefore('=')] = input.substringAfter('=')
                    }
                    "--pages" -> values["pages"] = value()
                    "--pdf-standard" -> values["pdfStandards"] = value().split(',')
                    "--features" -> values["features"] = value().split(',')
                    "--creation-timestamp" -> values["creationTimestampMillis"] = value().toLong() * 1000.0
                    "--no-pdf-tags" -> values["shouldTagPdf"] = false
                    "--pretty" -> values["shouldPrettyPrint"] = true
                    "--render-bleed" -> values["shouldRenderBleed"] = true
                    // Dedicated settings stay authoritative, as in the previous compiler flow.
                    "--root", "--format", "-f", "--ppi", "--font-path", "--package-path", "--package-cache-path" -> value()
                    "--ignore-system-fonts" -> Unit
                    else -> throw IllegalArgumentException("Unsupported WASM compiler option: $name")
                }
            }
            return TypstWasmOptions(values, inputs)
        }
    }
}
