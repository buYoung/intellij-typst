package com.livteam.typninja.language.highlighting

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Guards the color-settings page: every attribute descriptor resolves to a real (non-missing) bundle
 * message and a non-null key, the demo text is present, and the contextual-modifier preview tags are
 * wired. A missing bundle key would surface as a `!key!` marker, which this test rejects.
 */
class TypstColorSettingsPageTest : BasePlatformTestCase() {

    fun testEveryDescriptorHasAResolvedBundleKey() {
        val page = TypstColorSettingsPage()
        val descriptors = page.attributeDescriptors
        assertTrue("the page must expose attribute descriptors", descriptors.isNotEmpty())
        for (descriptor in descriptors) {
            val name = descriptor.displayName
            assertFalse("descriptor display name must not be a missing-key marker: '$name'", name.contains("!"))
            assertTrue("descriptor display name must not be blank", name.isNotBlank())
            assertNotNull("descriptor '$name' must have a text-attributes key", descriptor.key)
        }
    }

    fun testPageMetadataIsPresent() {
        val page = TypstColorSettingsPage()
        assertFalse("display name must resolve", page.displayName.contains("!"))
        assertTrue("display name must not be blank", page.displayName.isNotBlank())
        assertTrue("demo text must be present", page.demoText.isNotBlank())
        assertNotNull("highlighter must be provided", page.highlighter)
    }

    fun testContextualModifierPreviewTagsAreWired() {
        val map = TypstColorSettingsPage().additionalHighlightingTagToDescriptorMap
        assertNotNull("strong/emph preview tags must be registered", map)
        assertSame(TypstTextAttributeKeys.STRONG, map!!["strong"])
        assertSame(TypstTextAttributeKeys.EMPH, map["emph"])
    }
}
