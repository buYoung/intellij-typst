package com.livteam.typninja.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.livteam.typninja.execution.TypstToolchainService
import com.livteam.typninja.language.analysis.TypstProjectModelService
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class TypstSettingsConfigurable(private val project: Project) : Configurable {
    private var settingsComponent: TypstSettingsComponent? = null

    override fun getDisplayName(): String = "Typst"

    override fun createComponent(): JComponent = TypstSettingsComponent().also {
        settingsComponent = it
        reset()
    }.panel

    override fun isModified(): Boolean {
        val component = settingsComponent ?: return false
        val settings = TypstSettingsService.getInstance(project).state
        return component.rootPath != settings.rootPath.orEmpty() ||
            component.mainFilePath != settings.mainFilePath.orEmpty() ||
            component.useDefaultPackageRoots != settings.useDefaultPackageRoots ||
            component.packagePath != settings.packagePath.orEmpty() ||
            component.packageCachePath != settings.packageCachePath.orEmpty() ||
            component.syntaxOnlyMode != settings.syntaxOnlyMode ||
            component.enableSemanticHighlighting != settings.enableSemanticHighlighting ||
            component.enableSemanticDiagnostics != settings.enableSemanticDiagnostics ||
            component.enableLint != settings.enableLint ||
            component.showPackageVersionHints != settings.showPackageVersionHints ||
            component.enablePostfixCompletion != settings.enablePostfixCompletion ||
            component.enableUfcsCompletion != settings.enableUfcsCompletion ||
            component.enableUfcsLeftCompletion != settings.enableUfcsLeftCompletion ||
            component.enableUfcsRightCompletion != settings.enableUfcsRightCompletion ||
            component.enableOnEnter != settings.enableOnEnter ||
            component.typstExecutablePath != settings.typstExecutablePath.orEmpty() ||
            component.useSystemFonts != settings.useSystemFonts ||
            component.fontPaths != settings.fontPaths.orEmpty() ||
            component.typstExtraArguments != settings.typstExtraArguments.orEmpty() ||
            component.previewPpi != settings.previewPpi ||
            component.autoPreview != settings.autoPreview.orEmpty() ||
            component.previewArguments != settings.previewArguments.orEmpty() ||
            component.invertPreviewColors != settings.invertPreviewColors ||
            component.defaultExportFormat != settings.defaultExportFormat.orEmpty() ||
            component.autoExport != settings.autoExport.orEmpty() ||
            component.exportTarget != settings.exportTarget.orEmpty() ||
            component.outputPathPattern != settings.outputPathPattern.orEmpty() ||
            component.enableResourcePaste != settings.enableResourcePaste ||
            component.resourcePasteFolder != settings.resourcePasteFolder.orEmpty()
    }

    override fun apply() {
        val component = settingsComponent ?: return
        if (component.previewPpi !in MIN_PREVIEW_PPI..MAX_PREVIEW_PPI) {
            throw ConfigurationException("Preview PPI must be between $MIN_PREVIEW_PPI and $MAX_PREVIEW_PPI.")
        }
        if (component.defaultExportFormat !in EXPORT_FORMATS) {
            throw ConfigurationException("Unsupported export format: ${component.defaultExportFormat}")
        }
        val settings = TypstSettingsService.getInstance(project).state
        settings.rootPath = component.rootPath.trim()
        settings.mainFilePath = component.mainFilePath.trim()
        settings.useDefaultPackageRoots = component.useDefaultPackageRoots
        settings.packagePath = component.packagePath.trim()
        settings.packageCachePath = component.packageCachePath.trim()
        settings.syntaxOnlyMode = component.syntaxOnlyMode
        settings.enableSemanticHighlighting = component.enableSemanticHighlighting
        settings.enableSemanticDiagnostics = component.enableSemanticDiagnostics
        settings.enableLint = component.enableLint
        settings.showPackageVersionHints = component.showPackageVersionHints
        settings.enablePostfixCompletion = component.enablePostfixCompletion
        settings.enableUfcsCompletion = component.enableUfcsCompletion
        settings.enableUfcsLeftCompletion = component.enableUfcsLeftCompletion
        settings.enableUfcsRightCompletion = component.enableUfcsRightCompletion
        settings.enableOnEnter = component.enableOnEnter
        settings.typstExecutablePath = component.typstExecutablePath.trim()
        settings.useSystemFonts = component.useSystemFonts
        settings.fontPaths = component.fontPaths.trim()
        settings.typstExtraArguments = component.typstExtraArguments.trim()
        settings.previewPpi = component.previewPpi
        settings.autoPreview = component.autoPreview
        settings.previewArguments = component.previewArguments.trim()
        settings.invertPreviewColors = component.invertPreviewColors
        settings.defaultExportFormat = component.defaultExportFormat
        settings.autoExport = component.autoExport
        settings.exportTarget = component.exportTarget
        settings.outputPathPattern = component.outputPathPattern.trim()
        settings.enableResourcePaste = component.enableResourcePaste
        settings.resourcePasteFolder = component.resourcePasteFolder.trim()

        TypstProjectModelService.getInstance(project).requestRefresh()
        TypstToolchainService.getInstance(project).requestValidation()
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    override fun reset() {
        val component = settingsComponent ?: return
        val settings = TypstSettingsService.getInstance(project).state
        component.rootPath = settings.rootPath.orEmpty()
        component.mainFilePath = settings.mainFilePath.orEmpty()
        component.useDefaultPackageRoots = settings.useDefaultPackageRoots
        component.packagePath = settings.packagePath.orEmpty()
        component.packageCachePath = settings.packageCachePath.orEmpty()
        component.syntaxOnlyMode = settings.syntaxOnlyMode
        component.enableSemanticHighlighting = settings.enableSemanticHighlighting
        component.enableSemanticDiagnostics = settings.enableSemanticDiagnostics
        component.enableLint = settings.enableLint
        component.showPackageVersionHints = settings.showPackageVersionHints
        component.enablePostfixCompletion = settings.enablePostfixCompletion
        component.enableUfcsCompletion = settings.enableUfcsCompletion
        component.enableUfcsLeftCompletion = settings.enableUfcsLeftCompletion
        component.enableUfcsRightCompletion = settings.enableUfcsRightCompletion
        component.enableOnEnter = settings.enableOnEnter
        component.typstExecutablePath = settings.typstExecutablePath.orEmpty()
        component.useSystemFonts = settings.useSystemFonts
        component.fontPaths = settings.fontPaths.orEmpty()
        component.typstExtraArguments = settings.typstExtraArguments.orEmpty()
        component.previewPpi = settings.previewPpi
        component.autoPreview = settings.autoPreview.orEmpty().ifBlank { "onSave" }
        component.previewArguments = settings.previewArguments.orEmpty()
        component.invertPreviewColors = settings.invertPreviewColors
        component.defaultExportFormat = settings.defaultExportFormat.orEmpty().ifBlank { "pdf" }
        component.autoExport = settings.autoExport.orEmpty().ifBlank { "never" }
        component.exportTarget = settings.exportTarget.orEmpty().ifBlank { "paged" }
        component.outputPathPattern = settings.outputPathPattern.orEmpty()
        component.enableResourcePaste = settings.enableResourcePaste
        component.resourcePasteFolder = settings.resourcePasteFolder.orEmpty().ifBlank { "\$root/assets" }
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }

    private class TypstSettingsComponent {
        private val rootPathField = JBTextField()
        private val mainFilePathField = JBTextField()
        private val defaultRootsCheckBox = JBCheckBox("Use default local Typst package roots")
        private val packagePathField = JBTextField()
        private val packageCachePathField = JBTextField()

        private val syntaxOnlyCheckBox = JBCheckBox("Syntax-only mode")
        private val semanticHighlightingCheckBox = JBCheckBox("Color functions, variables, parameters, and fields")
        private val semanticDiagnosticsCheckBox = JBCheckBox("Show name and argument problems")
        private val lintCheckBox = JBCheckBox("Show safe code warnings")
        private val packageVersionHintsCheckBox = JBCheckBox("Show installed package version state")
        private val onEnterCheckBox = JBCheckBox("Continue comments, lists, and math indentation on Enter")

        private val postfixCheckBox = JBCheckBox("Enable postfix completion")
        private val ufcsCheckBox = JBCheckBox("Wrap content directly")
        private val ufcsLeftCheckBox = JBCheckBox("Place arguments before content")
        private val ufcsRightCheckBox = JBCheckBox("Place content as the first argument")

        private val executablePathField = JBTextField()
        private val systemFontsCheckBox = JBCheckBox("Use system fonts")
        private val fontPathsField = JBTextField()
        private val extraArgumentsField = JBTextField()

        private val previewPpiSpinner = JSpinner(SpinnerNumberModel(144, MIN_PREVIEW_PPI, MAX_PREVIEW_PPI, 1))
        private val autoPreviewComboBox = JComboBox(AUTO_TRIGGERS.toTypedArray())
        private val previewArgumentsField = JBTextField()
        private val invertPreviewCheckBox = JBCheckBox("Invert preview colors")

        private val exportFormatComboBox = JComboBox(EXPORT_FORMATS.toTypedArray())
        private val autoExportComboBox = JComboBox(AUTO_TRIGGERS.toTypedArray())
        private val exportTargetComboBox = JComboBox(EXPORT_TARGETS.toTypedArray())
        private val outputPathField = JBTextField()
        private val resourcePasteCheckBox = JBCheckBox("Copy pasted files into the project")
        private val resourcePasteFolderField = JBTextField()

        var rootPath by rootPathField::text
        var mainFilePath by mainFilePathField::text
        var useDefaultPackageRoots: Boolean
            get() = defaultRootsCheckBox.isSelected
            set(value) { defaultRootsCheckBox.isSelected = value }
        var packagePath by packagePathField::text
        var packageCachePath by packageCachePathField::text
        var syntaxOnlyMode: Boolean
            get() = syntaxOnlyCheckBox.isSelected
            set(value) { syntaxOnlyCheckBox.isSelected = value }
        var enableSemanticHighlighting: Boolean
            get() = semanticHighlightingCheckBox.isSelected
            set(value) { semanticHighlightingCheckBox.isSelected = value }
        var enableSemanticDiagnostics: Boolean
            get() = semanticDiagnosticsCheckBox.isSelected
            set(value) { semanticDiagnosticsCheckBox.isSelected = value }
        var enableLint: Boolean
            get() = lintCheckBox.isSelected
            set(value) { lintCheckBox.isSelected = value }
        var showPackageVersionHints: Boolean
            get() = packageVersionHintsCheckBox.isSelected
            set(value) { packageVersionHintsCheckBox.isSelected = value }
        var enableOnEnter: Boolean
            get() = onEnterCheckBox.isSelected
            set(value) { onEnterCheckBox.isSelected = value }
        var enablePostfixCompletion: Boolean
            get() = postfixCheckBox.isSelected
            set(value) { postfixCheckBox.isSelected = value }
        var enableUfcsCompletion: Boolean
            get() = ufcsCheckBox.isSelected
            set(value) { ufcsCheckBox.isSelected = value }
        var enableUfcsLeftCompletion: Boolean
            get() = ufcsLeftCheckBox.isSelected
            set(value) { ufcsLeftCheckBox.isSelected = value }
        var enableUfcsRightCompletion: Boolean
            get() = ufcsRightCheckBox.isSelected
            set(value) { ufcsRightCheckBox.isSelected = value }
        var typstExecutablePath by executablePathField::text
        var useSystemFonts: Boolean
            get() = systemFontsCheckBox.isSelected
            set(value) { systemFontsCheckBox.isSelected = value }
        var fontPaths by fontPathsField::text
        var typstExtraArguments by extraArgumentsField::text
        var previewPpi: Int
            get() = previewPpiSpinner.value as Int
            set(value) { previewPpiSpinner.value = value }
        var autoPreview: String
            get() = autoPreviewComboBox.selectedItem as String
            set(value) { autoPreviewComboBox.selectedItem = value }
        var previewArguments by previewArgumentsField::text
        var invertPreviewColors: Boolean
            get() = invertPreviewCheckBox.isSelected
            set(value) { invertPreviewCheckBox.isSelected = value }
        var defaultExportFormat: String
            get() = exportFormatComboBox.selectedItem as String
            set(value) { exportFormatComboBox.selectedItem = value }
        var autoExport: String
            get() = autoExportComboBox.selectedItem as String
            set(value) { autoExportComboBox.selectedItem = value }
        var exportTarget: String
            get() = exportTargetComboBox.selectedItem as String
            set(value) { exportTargetComboBox.selectedItem = value }
        var outputPathPattern by outputPathField::text
        var enableResourcePaste: Boolean
            get() = resourcePasteCheckBox.isSelected
            set(value) { resourcePasteCheckBox.isSelected = value }
        var resourcePasteFolder by resourcePasteFolderField::text

        val panel: JComponent = panel {
            group("Workspace") {
                row("Root folder:") { cell(rootPathField).align(AlignX.FILL) }
                row("Main Typst file:") { cell(mainFilePathField).align(AlignX.FILL) }
            }
            group("Packages") {
                row { cell(defaultRootsCheckBox) }
                row("Package folder:") { cell(packagePathField).align(AlignX.FILL) }
                row("Package cache folder:") { cell(packageCachePathField).align(AlignX.FILL) }
                row { cell(packageVersionHintsCheckBox) }
            }
            group("Editor") {
                row { cell(syntaxOnlyCheckBox) }
                row { cell(semanticHighlightingCheckBox) }
                row { cell(semanticDiagnosticsCheckBox) }
                row { cell(lintCheckBox) }
                row { cell(onEnterCheckBox) }
            }
            group("Completion") {
                row { cell(postfixCheckBox) }
                row { cell(ufcsCheckBox) }
                row { cell(ufcsLeftCheckBox) }
                row { cell(ufcsRightCheckBox) }
            }
            group("Typst and fonts") {
                row("Typst executable:") { cell(executablePathField).align(AlignX.FILL) }
                row { cell(systemFontsCheckBox) }
                row("Font paths:") { cell(fontPathsField).align(AlignX.FILL) }
                row("Extra Typst arguments:") { cell(extraArgumentsField).align(AlignX.FILL) }
            }
            group("Preview") {
                row("Automatic refresh:") { cell(autoPreviewComboBox) }
                row("Image PPI:") { cell(previewPpiSpinner) }
                row("Preview arguments:") { cell(previewArgumentsField).align(AlignX.FILL) }
                row { cell(invertPreviewCheckBox) }
            }
            group("Export and pasted files") {
                row("Default format:") { cell(exportFormatComboBox) }
                row("Automatic export:") { cell(autoExportComboBox) }
                row("Document target:") { cell(exportTargetComboBox) }
                row("Output path pattern:") { cell(outputPathField).align(AlignX.FILL) }
                row { cell(resourcePasteCheckBox) }
                row("Pasted file folder:") { cell(resourcePasteFolderField).align(AlignX.FILL) }
            }
        }
    }

    private companion object {
        const val MIN_PREVIEW_PPI = 72
        const val MAX_PREVIEW_PPI = 600
        val AUTO_TRIGGERS = listOf("never", "onSave", "onType")
        val EXPORT_FORMATS = listOf("pdf", "png", "svg", "html")
        val EXPORT_TARGETS = listOf("paged", "html", "bundle")
    }
}
