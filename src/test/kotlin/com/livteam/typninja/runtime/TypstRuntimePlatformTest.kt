package com.livteam.typninja.runtime

import com.livteam.typninja.settings.TypstSettingsService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypstRuntimePlatformTest {
    @Test
    fun `new settings use runtime integration defaults`() {
        val settings = TypstSettingsService.Settings()
        assertEquals("onSave", settings.compilerDiagnosticsTrigger)
        assertTrue(settings.autoDownloadPackages)
        assertTrue(settings.autoDownloadWasm)
    }
}
