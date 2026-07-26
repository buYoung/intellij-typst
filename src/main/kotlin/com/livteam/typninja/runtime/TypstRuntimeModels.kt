package com.livteam.typninja.runtime

enum class TypstRuntimeStatus {
    UNINSTALLED,
    DOWNLOADING,
    READY,
    INCOMPATIBLE,
    FAILED,
    CLI_FALLBACK,
}

enum class TypstPackageStatus {
    AVAILABLE_REMOTELY,
    DOWNLOADING,
    INSTALLED,
    FAILED,
}

data class TypstRuntimeDiagnostic(
    val severity: String,
    val message: String,
    val uri: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val trace: List<String> = emptyList(),
)

data class TypstRuntimePage(
    val number: Int,
    val width: Double? = null,
    val height: Double? = null,
)

data class TypstRuntimeCompileResult(
    val generation: Long,
    val documentVersion: Long,
    val outputStatus: String,
    val diagnostics: List<TypstRuntimeDiagnostic>,
    val pages: List<TypstRuntimePage>,
    val sourceMappingAvailable: Boolean,
    val previewUrl: String?,
)
