package com.livteam.typninja.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.livteam.typninja.language.analysis.TypstProjectModelService
import com.livteam.typninja.execution.TypstToolchainService
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class TypstSettingsConfigurable(private val project: Project) : Configurable {
    private var settingsComponent: TypstSettingsComponent? = null

    override fun getDisplayName(): String = "Typst"

    override fun createComponent(): JComponent {
        val component = TypstSettingsComponent()
        settingsComponent = component
        reset()
        return component.panel
    }

    override fun isModified(): Boolean {
        val component = settingsComponent ?: return false
        val settings = TypstSettingsService.getInstance(project).state
        return component.useDefaultPackageRoots != settings.useDefaultPackageRoots ||
            component.packagePath != settings.packagePath.orEmpty() ||
            component.packageCachePath != settings.packageCachePath.orEmpty() ||
            component.enableSemanticDiagnostics != settings.enableSemanticDiagnostics ||
            component.enableLint != settings.enableLint ||
            component.typstExecutablePath != settings.typstExecutablePath.orEmpty() ||
            component.previewPpi != settings.previewPpi ||
            component.defaultExportFormat != settings.defaultExportFormat.orEmpty()
    }

    override fun apply() {
        val component = settingsComponent ?: return
        if (component.previewPpi !in MIN_PREVIEW_PPI..MAX_PREVIEW_PPI) {
            throw ConfigurationException("Preview PPI must be between $MIN_PREVIEW_PPI and $MAX_PREVIEW_PPI.")
        }
        if (component.defaultExportFormat.lowercase() !in EXPORT_FORMATS) {
            throw ConfigurationException("Default export format must be one of: ${EXPORT_FORMATS.joinToString()}.")
        }
        val settings = TypstSettingsService.getInstance(project).state
        settings.useDefaultPackageRoots = component.useDefaultPackageRoots
        settings.packagePath = component.packagePath.trim()
        settings.packageCachePath = component.packageCachePath.trim()
        settings.enableSemanticDiagnostics = component.enableSemanticDiagnostics
        settings.enableLint = component.enableLint
        settings.typstExecutablePath = component.typstExecutablePath.trim()
        settings.previewPpi = component.previewPpi
        settings.defaultExportFormat = component.defaultExportFormat.lowercase()
        TypstProjectModelService.getInstance(project).requestRefresh()
        TypstToolchainService.getInstance(project).requestValidation()
    }

    override fun reset() {
        val component = settingsComponent ?: return
        val settings = TypstSettingsService.getInstance(project).state
        component.useDefaultPackageRoots = settings.useDefaultPackageRoots
        component.packagePath = settings.packagePath.orEmpty()
        component.packageCachePath = settings.packageCachePath.orEmpty()
        component.enableSemanticDiagnostics = settings.enableSemanticDiagnostics
        component.enableLint = settings.enableLint
        component.typstExecutablePath = settings.typstExecutablePath.orEmpty()
        component.previewPpi = settings.previewPpi
        component.defaultExportFormat = settings.defaultExportFormat.orEmpty().ifBlank { "pdf" }
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }

    private class TypstSettingsComponent {
        private val defaultRootsCheckBox = JCheckBox("Use default local Typst package roots")
        private val packagePathField = JTextField()
        private val packageCachePathField = JTextField()
        private val semanticDiagnosticsCheckBox = JCheckBox("Enable semantic diagnostics")
        private val lintCheckBox = JCheckBox("Enable conservative lint diagnostics")
        private val executablePathField = JTextField()
        private val previewPpiSpinner = JSpinner(SpinnerNumberModel(144, MIN_PREVIEW_PPI, MAX_PREVIEW_PPI, 1))
        private val exportFormatField = JTextField()

        var useDefaultPackageRoots: Boolean
            get() = defaultRootsCheckBox.isSelected
            set(value) { defaultRootsCheckBox.isSelected = value }
        var packagePath: String
            get() = packagePathField.text
            set(value) { packagePathField.text = value }
        var packageCachePath: String
            get() = packageCachePathField.text
            set(value) { packageCachePathField.text = value }
        var enableSemanticDiagnostics: Boolean
            get() = semanticDiagnosticsCheckBox.isSelected
            set(value) { semanticDiagnosticsCheckBox.isSelected = value }
        var enableLint: Boolean
            get() = lintCheckBox.isSelected
            set(value) { lintCheckBox.isSelected = value }
        var typstExecutablePath: String
            get() = executablePathField.text
            set(value) { executablePathField.text = value }
        var previewPpi: Int
            get() = previewPpiSpinner.value as Int
            set(value) { previewPpiSpinner.value = value }
        var defaultExportFormat: String
            get() = exportFormatField.text
            set(value) { exportFormatField.text = value }

        val panel: JComponent = JPanel().apply {
            layout = GridLayout(3, 1, 0, 8)
            add(section("Packages", defaultRootsCheckBox, row("Package path:", packagePathField), row("Package cache path:", packageCachePathField)))
            add(section("Analysis", semanticDiagnosticsCheckBox, lintCheckBox))
            add(section("Preview and export", row("Typst executable:", executablePathField), row("Preview PPI:", previewPpiSpinner), row("Default export format:", exportFormatField)))
        }

        private fun section(title: String, vararg components: JComponent): JComponent = JPanel(GridLayout(components.size, 1, 0, 4)).apply {
            border = BorderFactory.createTitledBorder(title)
            components.forEach(::add)
        }

        private fun row(label: String, component: JComponent): JComponent = JPanel(BorderLayout(8, 0)).apply {
            add(JLabel(label), BorderLayout.WEST)
            add(component, BorderLayout.CENTER)
        }
    }

    private companion object {
        const val MIN_PREVIEW_PPI = 72
        const val MAX_PREVIEW_PPI = 600
        val EXPORT_FORMATS = listOf("pdf", "png", "svg")
    }
}
