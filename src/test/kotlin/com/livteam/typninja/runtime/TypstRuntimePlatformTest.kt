package com.livteam.typninja.runtime

import com.livteam.typninja.settings.TypstSettingsService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypstRuntimePlatformTest {
    @Test
    fun `selects every supported runtime platform`() {
        assertEquals("aarch64-apple-darwin", TypstRuntimePlatform.current("Mac OS X", "arm64"))
        assertEquals("x86_64-apple-darwin", TypstRuntimePlatform.current("Mac OS X", "x86_64"))
        assertEquals("x86_64-pc-windows-msvc", TypstRuntimePlatform.current("Windows 11", "amd64"))
        assertEquals("x86_64-unknown-linux-gnu", TypstRuntimePlatform.current("Linux", "x86_64"))
        assertNull(TypstRuntimePlatform.current("Linux", "aarch64"))
    }

    @Test
    fun `new settings use runtime integration defaults`() {
        val settings = TypstSettingsService.Settings()
        assertEquals("onSave", settings.compilerDiagnosticsTrigger)
        assertTrue(settings.autoDownloadPackages)
        assertTrue(settings.useNativeRenderer)
        assertTrue(settings.autoDownloadRenderer)
    }
}
